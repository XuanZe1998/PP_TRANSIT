const TARGET_BYTES = Math.round(1.5 * 1024 * 1024)
const MAX_UPLOAD_BYTES = 5 * 1024 * 1024

export function fitWithin(width: number, height: number, maxWidth = 1600, maxHeight = 900) {
  if (width <= 0 || height <= 0) throw new Error('无法读取图片尺寸')
  const scale = Math.min(1, maxWidth / width, maxHeight / height)
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale))
  }
}

function canvasBlob(canvas: HTMLCanvasElement, quality: number) {
  return new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/webp', quality))
}

export async function compressServiceImage(file: File): Promise<File> {
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file)
  } catch {
    if (file.size <= MAX_UPLOAD_BYTES) return file
    throw new Error('图片解码或压缩失败，且原文件超过 5 MB，请换一张图片后重试')
  }

  try {
    let size = fitWithin(bitmap.width, bitmap.height)
    let best: Blob | null = null
    for (let round = 0; round < 4; round += 1) {
      const canvas = document.createElement('canvas')
      canvas.width = size.width
      canvas.height = size.height
      const context = canvas.getContext('2d')
      if (!context) throw new Error('浏览器无法创建图片压缩画布')
      context.drawImage(bitmap, 0, 0, size.width, size.height)
      for (const quality of [0.88, 0.78, 0.68, 0.58]) {
        const blob = await canvasBlob(canvas, quality)
        if (blob && (!best || blob.size < best.size)) best = blob
        if (blob && blob.size <= TARGET_BYTES) {
          const baseName = file.name.replace(/\.[^.]+$/, '') || 'service-image'
          return new File([blob], `${baseName}.webp`, { type: 'image/webp', lastModified: Date.now() })
        }
      }
      size = { width: Math.max(1, Math.round(size.width * 0.82)), height: Math.max(1, Math.round(size.height * 0.82)) }
    }
    if (best && best.size <= MAX_UPLOAD_BYTES) {
      const baseName = file.name.replace(/\.[^.]+$/, '') || 'service-image'
      return new File([best], `${baseName}.webp`, { type: 'image/webp', lastModified: Date.now() })
    }
    if (file.size <= MAX_UPLOAD_BYTES) return file
    throw new Error('图片压缩后仍超过 5 MB，请降低分辨率后重试')
  } finally {
    bitmap.close()
  }
}
