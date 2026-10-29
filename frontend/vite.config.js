import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({plugins:[vue()],server:{proxy:{'/api':{target:process.env.ORDERING_API_TARGET || 'http://localhost:8080',changeOrigin:true,rewrite:path=>path.replace(/^\/api/,'')},'/upload':{target:process.env.ORDERING_API_TARGET || 'http://localhost:8080',changeOrigin:true}}}})
