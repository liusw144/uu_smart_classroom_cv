"""验证合法上传的极端图片明确失败，且后续正常作业可继续执行。"""

import json
import struct
import time
import zlib

from local_dev import ROOT, load_values
from verify_edge_offline import multipart, request


def narrow_png(width=20, height=8000):
    def chunk(kind, value):
        data=kind+value
        return struct.pack('!I',len(value))+data+struct.pack('!I',zlib.crc32(data)&0xffffffff)
    pixels=(b'\0'+b'\0\0\0'*width)*height
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',width,height,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')


def main():
    values=load_values()
    token=request('POST','/api/v1/auth/login',value={'school_code':'local-school','username':values['TEACHER_USERNAME'],'password':values['TEACHER_PASSWORD']})['access_token']
    task=request('GET','/api/v1/tasks?q=开源模型验证&size=100',token)['items'][0]
    model=request('GET','/api/v1/models?q=yolox-tiny-rtmpose-s-v1&size=100',token)['items'][0]
    evidence={'checks':[]}
    for filename,kind,value,expected in [('narrow-synthetic.png','image/png',narrow_png(),'FAILED'),('public-image.jpg','image/jpeg',(ROOT/'runtime/demo/image.jpg').read_bytes(),'SUCCEEDED')]:
        payload,content_type=multipart([('file',filename,kind,value)])
        asset=request('POST','/api/v1/assets',token,raw=payload,content_type=content_type)
        job=request('POST','/api/v1/detections',token,{'task_id':task['id'],'asset_id':asset['id'],'model_id':model['id']})
        deadline=time.monotonic()+60
        while job['status'] in ('QUEUED','RUNNING') and time.monotonic()<deadline:
            time.sleep(1)
            job=request('GET','/api/v1/detections/'+job['id'],token)
        assert job['status']==expected,job['status']
        if expected=='FAILED': assert job['error_code']=='INFERENCE_FAILED'
        else:
            result=request('GET','/api/v1/detections/'+job['id']+'/result',token)
            assert result['person_instances']==5
        evidence['checks'].append({'filename':filename,'job_id':job['id'],'status':job['status'],'error_code':job['error_code']})
    request('POST','/api/v1/auth/logout',token)
    evidence['passed']=True
    (ROOT/'runtime/real-demo-evidence/input-failure-evidence.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(evidence,ensure_ascii=False,indent=2))


if __name__=='__main__': main()
