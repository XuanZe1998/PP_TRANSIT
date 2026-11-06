import http from './http'

export async function downloadFile(endpoint: string, filename: string) {
  const { data } = await http.get<Blob>(endpoint, { responseType: 'blob' })
  const url = URL.createObjectURL(data)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  try { anchor.click() } finally {
    anchor.remove()
    // Safari needs the URL to remain alive after the click has been dispatched.
    setTimeout(() => URL.revokeObjectURL(url), 60_000)
  }
}
