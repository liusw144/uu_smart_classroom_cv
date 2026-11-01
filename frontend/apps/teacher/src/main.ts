import { createApp } from 'vue'
import App from '@uu/shared/App.vue'
import '@uu/shared/style.css'

createApp(App, { audience: 'teacher' }).mount('#app')
