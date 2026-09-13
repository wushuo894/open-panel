import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import {VitePWA} from 'vite-plugin-pwa'

let serverHost = process.env['SERVER_HOST'];

export default defineConfig({
    base: './',
    plugins: [
        vue(),
        vuetify({autoImport: true}),
        VitePWA({
            registerType: 'autoUpdate',
            manifest: false,
            includeAssets: ['icons/icon.svg', 'icons/icon-192.png', 'icons/icon-512.png', 'assets/default-wallpaper.webp'],
            workbox: {
                navigateFallback: 'index.html',
                navigateFallbackDenylist: [/\/api\//],
                runtimeCaching: [
                    {
                        urlPattern: /\/api\/public\/panel/,
                        handler: 'NetworkFirst',
                        options: {
                            cacheName: 'open-panel-public',
                            networkTimeoutSeconds: 4,
                            expiration: {maxEntries: 4, maxAgeSeconds: 86400}
                        }
                    }
                ]
            }
        })
    ],
    server: {
        port: 37788,
        proxy: {
            '/api': serverHost ? serverHost : 'http://127.0.0.1:7788',
            '/assets/uploads': serverHost ? serverHost : 'http://127.0.0.1:7788',
            '/manifest.webmanifest': serverHost ? serverHost : 'http://127.0.0.1:7788'
        }
    },
    build: {
        outDir: 'dist',
        sourcemap: false
    }
})
