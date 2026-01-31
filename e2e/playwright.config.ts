import { defineConfig } from '@playwright/test';

export default defineConfig({
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    headless: true,
    screenshot: 'on',
  },
});
