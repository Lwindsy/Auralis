import js from "@eslint/js";
import globals from "globals";
export default [
  {
    ignores: [
      "dist/**",
      "src/generated/**",
      "test-results/**",
      "playwright-report/**",
    ],
  },
  js.configs.recommended,
  { languageOptions: { globals: { ...globals.browser, ...globals.node } } },
];
