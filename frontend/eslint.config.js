import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import prettier from 'eslint-config-prettier'
import globals from 'globals'
import { defineConfig } from 'eslint/config'

export default defineConfig([
  {
    ignores: ['dist/**', 'coverage/**', 'node_modules/**']
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.{js,mjs,cjs,ts,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.node }
    }
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: { parser: tseslint.parser }
    }
  },
  {
    // Node shapes are generated from the ontology registry. Re-declaring one by hand is how the
    // model drifted between backend and frontend before #20.
    files: ['src/**/*.ts', 'src/**/*.vue'],
    ignores: ['src/generated/**'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector:
            'TSInterfaceDeclaration[id.name=/^(Repository|Team|Service|Pipeline|Artifact|Deployment|Environment|CloudResource|ConfigurationItem|Provenance)$/]',
          message:
            'This shape is generated from the ontology registry. Import it from @/generated/ontology instead.'
        }
      ]
    }
  },
  // Must be last: turns off formatting rules that would conflict with Prettier.
  prettier
])
