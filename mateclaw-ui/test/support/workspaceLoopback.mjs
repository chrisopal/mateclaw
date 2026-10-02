import { createServer } from 'node:http'

export async function startWorkspaceLoopback() {
  let received
  let slowStarted
  const server = createServer(async (request, response) => {
    response.setHeader('Access-Control-Allow-Origin', '*')
    response.setHeader('Access-Control-Allow-Headers', 'X-Workspace-Id,Content-Type')
    if (request.method === 'OPTIONS') {
      response.end()
      return
    }
    if (request.url === '/slow') {
      slowStarted?.()
      return
    }
    const chunks = []
    for await (const chunk of request) chunks.push(Buffer.from(chunk))
    received = {
      scope: request.headers['x-workspace-id'],
      contentType: request.headers['content-type'],
      body: Buffer.concat(chunks).toString(),
    }
    if (request.url === '/bytes') {
      response.setHeader('Content-Type', 'application/octet-stream')
      response.end(Buffer.from([0, 1, 127, 128, 255]))
    } else {
      response.setHeader('Content-Type', 'application/json')
      response.end(JSON.stringify({ code: 200, data: { sourceId: 'source-1' } }))
    }
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  if (!address || typeof address === 'string') throw new Error('Missing loopback port')
  return {
    baseURL: `http://127.0.0.1:${address.port}`,
    get received() {
      return received
    },
    reset() {
      received = undefined
      slowStarted = undefined
    },
    waitForSlowRequest() {
      return new Promise((resolve) => {
        slowStarted = resolve
      })
    },
    async close() {
      server.closeAllConnections()
      await new Promise((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      )
    },
  }
}
