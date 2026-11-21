import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import App from './App'

describe('application foundation', () => {
  it('renders the platform identity and honest service statuses', () => {
    const html = renderToStaticMarkup(<App />)
    expect(html).toContain('<main>')
    expect(html).toContain('<h1>Enterprise AI Agent Platform</h1>')
    expect(html).toContain('AI agent orchestration and management platform.')
    expect(html).toContain('aria-labelledby="development-status"')
    expect(html).toContain('<dt>Control Plane</dt><dd>Not connected</dd>')
    expect(html).toContain('<dt>Agent Runtime</dt><dd>Not connected</dd>')
  })
})
