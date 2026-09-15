import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import {VitePWA} from 'vite-plugin-pwa'
import viteCompression from 'vite-plugin-compression'

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
        }),
        viteCompression({
            algorithm: 'gzip',
            ext: '.gz',
            threshold: 10240,
            deleteOriginFile: false
        }),
        viteCompression({
            algorithm: 'brotliCompress',
            ext: '.br',
            threshold: 10240,
            deleteOriginFile: false
        })
    ],
    server: {
        port: 37788,
        proxy: {
            '/api': {
                target: serverHost ? serverHost : 'http://127.0.0.1:57788',
                changeOrigin: true,
                secure: false
            },
            '/assets/uploads': {
                target: serverHost ? serverHost : 'http://127.0.0.1:57788',
                changeOrigin: true,
                secure: false
            },
            '/manifest.webmanifest': {
                target: serverHost ? serverHost : 'http://127.0.0.1:57788',
                changeOrigin: true,
                secure: false
            }
        }
    },
    build: {
        outDir: 'dist',
        sourcemap: false
    }
})
