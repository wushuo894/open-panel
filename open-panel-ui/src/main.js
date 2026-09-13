import { createApp } from 'vue'
import { registerSW } from 'virtual:pwa-register'
import App from './App.vue'
import router from './router'
import vuetify from './plugins/vuetify'
import './styles/main.css'

registerSW({ immediate: true })
createApp(App).use(router).use(vuetify).mount('#app')
