/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    extend: {
      colors: {
        dolby: {
          bg: "#0B0E14",
          surface: "#141923",
          card: "#1B2230",
          border: "#1E2638",
          accent: "#7BA7FF",
          secondary: "#2B79D9",
          textPrimary: "#F1F5F9",
          textSecondary: "#94A3B8",
        },
      },
    },
  },
  plugins: [],
};
