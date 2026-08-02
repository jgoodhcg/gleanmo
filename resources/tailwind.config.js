module.exports = {
  content: [
    './src/**/*',
    // Classes applied from JS are invisible to a scan of src/ alone and get
    // purged. `.is-submitting` (the double-submit guard) is defined in
    // tailwind.css and only ever referenced here.
    './resources/public/js/**/*.js',
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Space Mono', 'monospace'],
      },
      colors: {
        // Custom neon colors from CSS variables
        'neon-gold': 'var(--gold)',
        'neon-azure': 'var(--azure)', 
        'neon-violet': 'var(--neon-violet)',
        'neon-cyan': 'var(--pacific-cyan)',
        'neon-amber': 'var(--gamboge)',
        'neon-pink': 'var(--rose-bonbon)',
        'neon-lime': 'var(--lime-green)',
        // Dark theme colors
        'dark-bg': 'var(--dark-bg)',
        'dark-surface': 'var(--dark-surface)',
        'dark-border': 'var(--dark-border)',
        'dark-text': 'var(--dark-text-primary)',
        'dark-text-secondary': 'var(--dark-text-secondary)',
      },
    },
  },
  plugins: [
    require('@tailwindcss/forms'),
  ],
}
