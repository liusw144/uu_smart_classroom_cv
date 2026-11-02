import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / '.env.local'


def load_values():
    if not ENV_FILE.is_file():
        raise SystemExit('请先按本机指南配置Git忽略的.env.local。')
    return {
        key.strip(): value.strip()
        for line in ENV_FILE.read_text(encoding='utf-8-sig').splitlines()
        if line.strip() and not line.lstrip().startswith('#') and '=' in line
        for key, value in [line.split('=', 1)]
    }


def mysql(values, sql):
    executable = values.get('MYSQL_EXE') or shutil.which('mysql')
    if not executable:
        candidate = Path('D:/software/mysql8.4.8/bin/mysql.exe')
        executable = str(candidate) if candidate.is_file() else None
    if not executable:
        raise SystemExit('未找到mysql客户端，请配置MYSQL_EXE。')
    config = ROOT / '.harness/local-mysql.cnf'
    config.parent.mkdir(exist_ok=True)
    entries = {'host': values.get('DB_HOST', '127.0.0.1'), 'port': values.get('DB_PORT', '3306'),
               'user': values['DB_ADMIN_USER'], 'password': values['DB_ADMIN_PASSWORD']}
    if any('\n' in value or '\r' in value for value in entries.values()):
        raise SystemExit('数据库配置不能包含换行。')
    config.write_text('[client]\n' + ''.join(f'{key}="{value.replace(chr(92), chr(92)*2).replace(chr(34), chr(92)+chr(34))}"\n' for key, value in entries.items()), encoding='utf-8')
    try:
        result = subprocess.run([executable, f'--defaults-extra-file={config}', '--batch', '--skip-column-names', '--default-character-set=utf8mb4'], input=sql, text=True, encoding='utf-8', capture_output=True)
        if result.returncode:
            raise SystemExit('数据库初始化失败；请检查连接、授权及专用库状态。未输出含凭据的SQL。')
        return result.stdout.strip()
    finally:
        config.unlink(missing_ok=True)


def initialize(values, account_host='127.0.0.1'):
    if account_host not in {'127.0.0.1','%'} or (account_host=='%' and os.environ.get('GITHUB_ACTIONS')!='true'):
        raise SystemExit('通配连接来源只允许GitHub临时服务容器；本机账号保持回环限制。')
    names = {'uu_classroom_local', 'uu_classroom_test'}
    found = mysql(values, "SELECT TABLE_SCHEMA,TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('uu_classroom_local','uu_classroom_test');")
    allowed = {'flyway_schema_history', 'tenant', 'school_account', 'school_class', 'course', 'lesson_task', 'lesson_task_view', 'school_notice', 'operation_log', 'model_catalog', 'behavior_category', 'media_asset', 'detection_job', 'detection_job_view', 'analysis_report', 'edge_node'}
    for line in found.splitlines():
        if line.split('\t')[-1] not in allowed:
            raise SystemExit('目标专用库已有非本项目表，停止初始化，未修改数据。')
    additions = {'DB_USER': 'uu_cv_app_' + secrets.token_hex(3), 'DB_PASSWORD': secrets.token_urlsafe(30),
                 'TEST_DB_USER': 'uu_cv_test_' + secrets.token_hex(3), 'TEST_DB_PASSWORD': secrets.token_urlsafe(30),
                 'BOOTSTRAP_PASSWORD': secrets.token_urlsafe(18), 'VISION_SERVICE_TOKEN': secrets.token_urlsafe(36),
                 'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': '6379', 'REDIS_PASSWORD': '',
                 'REDIS_DATABASE': '14', 'TEST_REDIS_DATABASE': '15'}
    for key, value in additions.items():
        values.setdefault(key, value)
    statements = []
    for database, user_key, password_key in [('uu_classroom_local', 'DB_USER', 'DB_PASSWORD'), ('uu_classroom_test', 'TEST_DB_USER', 'TEST_DB_PASSWORD')]:
        user, password = values[user_key], values[password_key]
        if not user.replace('_', '').isalnum() or any(char in password for char in "'\\\r\n"):
            raise SystemExit('专用账号格式无效，停止初始化。')
        statements.extend([f'CREATE DATABASE IF NOT EXISTS `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;',
                           f"CREATE USER IF NOT EXISTS '{user}'@'{account_host}' IDENTIFIED BY '{password}';",
                           f"GRANT ALL PRIVILEGES ON `{database}`.* TO '{user}'@'{account_host}';"])
    mysql(values, '\n'.join(statements))
    ENV_FILE.write_text('# 仅供本机使用，禁止提交或分享\n' + '\n'.join(f'{key}={value}' for key, value in values.items()) + '\n', encoding='utf-8')
    print('专用业务库与测试库已准备；随机应用凭据及初始化密码仅保存在.env.local。')


def environment(values, test=False, target='backend'):
    prefixes=('DB_','TEST_DB_','REDIS_','TEST_REDIS_','BOOTSTRAP_','VISION_','CLASSROOM_','EDGE_','AI_','LLAMA_','MODEL_')
    result = {key:value for key,value in os.environ.items() if not key.startswith(prefixes)}
    allowed={'DB_USER','DB_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD','REDIS_DATABASE','BOOTSTRAP_PASSWORD','VISION_SERVICE_TOKEN'}
    result.update({key:value for key,value in values.items() if key in allowed})
    database = 'uu_classroom_test' if test else 'uu_classroom_local'
    result['DB_URL'] = f"jdbc:mysql://{values.get('DB_HOST','127.0.0.1')}:{values.get('DB_PORT','3306')}/{database}?serverTimezone=UTC&characterEncoding=UTF-8"
    if test:
        result.update(DB_USER=values['TEST_DB_USER'], DB_PASSWORD=values['TEST_DB_PASSWORD'], BOOTSTRAP_ENABLED='false', REDIS_DATABASE=values['TEST_REDIS_DATABASE'])
    else:
        result['BOOTSTRAP_ENABLED'] = 'true'
    result.update(SERVER_ADDRESS='127.0.0.1', PYTHONUTF8='1', VISION_URL='http://127.0.0.1:5000', CLASSROOM_VISION_SERVICE_TOKEN=values['VISION_SERVICE_TOKEN'], CLASSROOM_REDIS_COMPATIBILITY_MODE='true')
    result['CLASSROOM_ASSETS_ROOT'] = str(ROOT / ('.harness/test-assets' if test else 'runtime/assets'))
    result['CLASSROOM_ARTIFACT_ROOT'] = str(ROOT / ('.harness/test-artifacts' if test else 'runtime/artifacts'))
    result['MODEL_LOCK_PATH'] = str(ROOT / 'models/demo-assets.lock.json')
    result['UV_CACHE_DIR'] = str(ROOT / 'runtime/cache/uv')
    if not test:
        result['SPRING_PROFILES_ACTIVE'] = 'dev'
        for key in ('EDGE_NODE_ID','EDGE_SERVICE_TOKEN','AI_SERVICE_URL','AI_SERVICE_TOKEN'):
            if key in values: result[key]=values[key]
        result['CLASSROOM_PIPELINE_ENABLED']=values.get('REAL_DEMO_ENABLED','false')
    java = Path(result.get('JAVA_HOME', ''))
    if not (java / 'bin/javac.exe').is_file() or 'jdk21' not in str(java).lower():
        java = Path(values.get('LOCAL_JAVA_HOME', 'D:/software/java/jdk21'))
    if (java / 'bin/java.exe').is_file():
        result['JAVA_HOME'] = str(java)
        result['PATH'] = str(java / 'bin') + os.pathsep + result['PATH']
    if target in {'teacher','admin','vision','edge','ai'}:
        result = {key:value for key,value in result.items() if not key.startswith(prefixes)}
        if target in {'vision','edge'}:
            result['VISION_SERVICE_TOKEN']=values['VISION_SERVICE_TOKEN']
            result['VISION_URL']='http://127.0.0.1:5000'
        if target=='vision':
            result['VISION_APP_ENV']='development'
            result.update(VISION_ENGINE='onnx' if values.get('REAL_DEMO_ENABLED')=='true' else 'unavailable', VISION_MODEL_LOCK=str(ROOT/'models/demo-assets.lock.json'),VISION_MODELS_ROOT=str(ROOT/'runtime/models'),VISION_ASSET_CACHE=str(ROOT/'runtime/edge/assets'),VISION_RESULT_ROOT=str(ROOT/'runtime/edge/results'))
        if target=='edge':
            result.update(EDGE_NODE_ID=values['EDGE_NODE_ID'],EDGE_SERVICE_TOKEN=values['EDGE_SERVICE_TOKEN'],EDGE_CONTROL_URL='http://127.0.0.1:8089',EDGE_STATE_ROOT=str(ROOT/'runtime/edge'),EDGE_LEASE_SCHEMA=str(ROOT/'contracts/edge-lease.schema.json'))
        if target=='ai': result['LLAMA_API_KEY']=values['AI_SERVICE_TOKEN']
    return result


def seed(values):
    import json
    import urllib.request
    def request(method,path,data=None,token=None):
        payload=None if data is None else json.dumps(data).encode('utf-8')
        headers={'Content-Type':'application/json'}
        if token: headers['Authorization']='Bearer '+token
        req=urllib.request.Request('http://127.0.0.1:8089/api/v1'+path,data=payload,headers=headers,method=method)
        with urllib.request.urlopen(req,timeout=15) as response: return json.load(response)['data']
    token=request('POST','/auth/login',{'school_code':'local-school','username':'admin','password':values['BOOTSTRAP_PASSWORD']})['access_token']
    username=values.get('TEACHER_USERNAME','teacher')
    users=request('GET','/users?q='+username,token=token)['items']
    if not any(user['username']==username for user in users):
        password=secrets.token_urlsafe(18)
        request('POST','/users',{'username':username,'password':password,'display_name':'本机教师','role':'TEACHER'},token)
        values.update(TEACHER_USERNAME=username,TEACHER_PASSWORD=password)
    code='yolox-tiny-rtmpose-s-v1' if values.get('REAL_DEMO_ENABLED')=='true' else 'local-yolox-rtmpose'
    if not request('GET','/models?q='+code,token=token)['items']:
        request('POST','/models',{'name':'YOLOX与RTMPose本机样例','code':code,'description':'锁定权重的CPU演示；尚无课堂精度结论。','enabled':True},token)
    ENV_FILE.write_text('# 仅供本机使用，禁止提交或分享\n'+'\n'.join(f'{key}={value}' for key,value in values.items())+'\n',encoding='utf-8')
    request('POST','/auth/logout',token=token)
    print('本机教师账号和模型档案已准备；账号密码只在.env.local中查看。')


def main():
    parser = argparse.ArgumentParser(description='使用本机数据库运行最小业务版本')
    parser.add_argument('action', choices=['init', 'seed', 'test', 'build', 'backend', 'teacher', 'admin', 'vision','edge','ai'])
    parser.add_argument('extra', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    values = load_values()
    if args.action in {'backend','edge'} and (ROOT/'runtime/maintenance.lock').exists():
        raise SystemExit('项目处于备份恢复维护窗口，拒绝启动写入服务。')
    if args.action == 'ai':
        lock=json.loads((ROOT/'models/demo-assets.lock.json').read_text(encoding='utf-8'))
        with (ROOT/'runtime'/lock['ai']['path']).open('rb') as source:
            if hashlib.file_digest(source,'sha256').hexdigest()!=lock['ai']['sha256']:
                raise SystemExit('AI权重哈希与锁定清单不符，拒绝启动。')
    if args.action == 'init':
        initialize(values)
        return
    if args.action == 'seed':
        seed(values)
        return
    env = environment(values, args.action == 'test', args.action)
    commands = {
        'test': (['mvn.cmd' if os.name == 'nt' else 'mvn', '-s', '.mvn/settings.xml', '-B', '-Pintegration', 'verify'], 'backend'),
        'build': (['mvn.cmd' if os.name == 'nt' else 'mvn', '-s', '.mvn/settings.xml', '-B', 'verify'], 'backend'),
        'backend': ([str(Path(env['JAVA_HOME']) / 'bin/java.exe'), '-jar', 'api/target/classroom-api-0.1.0-SNAPSHOT.jar'], 'backend'),
        'teacher': (['npm.cmd' if os.name == 'nt' else 'npm', 'exec', '--', 'vite', 'apps/teacher', '--host', '127.0.0.1', '--port', '5173', '--strictPort'], 'frontend'),
        'admin': (['npm.cmd' if os.name == 'nt' else 'npm', 'exec', '--', 'vite', 'apps/admin', '--host', '127.0.0.1', '--port', '5172', '--strictPort'], 'frontend'),
        'vision': (['uv', 'run', 'uvicorn', 'classroom_vision.main:create_app', '--factory', '--host', '127.0.0.1', '--port', '5000'], 'fastapi'),
        'edge': (['uv','run','python','-m','classroom_edge.worker',*args.extra], 'edge'),
        'ai': ([str(ROOT/'runtime/vendor/llama/llama-server.exe'),'-m',str(ROOT/'runtime/models/Qwen3-0.6B-Q8_0.gguf'),'--host','127.0.0.1','--port','18188','--alias','qwen3-local','-c','2048','-t','4','--parallel','1','-ngl','0','--no-ui','--jinja'],'.'),
    }
    command, folder = commands[args.action]
    if args.action in {'test', 'build'}:
        command.extend(args.extra)
    raise SystemExit(subprocess.call(command, cwd=ROOT / folder, env=env))


if __name__ == '__main__':
    main()
