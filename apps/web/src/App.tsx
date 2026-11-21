const services = ['Control Plane', 'Agent Runtime'] as const

function App() {
  return (
    <main>
      <h1>Enterprise AI Agent Platform</h1>
      <p>AI agent orchestration and management platform.</p>
      <section aria-labelledby="development-status">
        <h2 id="development-status">Development status</h2>
        <dl>
          {services.map((service) => (
            <div key={service}>
              <dt>{service}</dt>
              <dd>Not connected</dd>
            </div>
          ))}
        </dl>
      </section>
    </main>
  )
}

export default App
