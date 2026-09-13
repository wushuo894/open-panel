import '@mdi/font/css/materialdesignicons.css'
import 'vuetify/styles'
import { createVuetify } from 'vuetify'

export default createVuetify({
  theme: {
    defaultTheme: 'openPanelLight',
    themes: {
      openPanelLight: {
        dark: false,
        colors: {
          background: '#f4f5f2',
          surface: '#ffffff',
          primary: '#202724',
          secondary: '#d8f257',
          accent: '#e9ff70',
          error: '#c64040',
          info: '#327c88',
          success: '#3e8060',
          warning: '#a56b22'
        }
      },
      openPanelDark: {
        dark: true,
        colors: {
          background: '#101418',
          surface: '#181e22',
          primary: '#e9ff70',
          secondary: '#8fa64b',
          accent: '#e9ff70',
          error: '#ef767a',
          info: '#72bdc5',
          success: '#76b995',
          warning: '#d6a35c'
        }
      }
    }
  },
  defaults: {
    VBtn: { rounded: 'lg' },
    VCard: { rounded: 'lg', elevation: 0 },
    VTextField: { variant: 'outlined', density: 'comfortable' },
    VSelect: { variant: 'outlined', density: 'comfortable' },
    VTextarea: { variant: 'outlined', density: 'comfortable' }
  }
})
