<template>
  <div class="tech-backdrop" :class="{ 'is-home': home, 'is-quiet': quiet, 'is-paused': paused }" aria-hidden="true">
    <div class="tech-aurora"></div>
    <canvas ref="particleCanvas" class="particle-canvas"></canvas>

    <svg class="circuit-traces" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice">
      <g class="circuit circuit-a">
        <path d="M-40 165H190L246 221H474L534 161H746" />
        <path d="M103 0V95L162 154V278L224 340H410" />
        <path d="M1170 -20V112L1108 174H930L864 240H726" />
        <circle cx="246" cy="221" r="5" /><circle cx="534" cy="161" r="5" /><circle cx="1108" cy="174" r="5" />
      </g>
      <g class="circuit circuit-b">
        <path d="M1480 668H1258L1192 602H1012L942 672H760" />
        <path d="M1337 900V804L1274 741V626L1210 562H1080" />
        <path d="M-30 744H166L232 678H398L472 752H650" />
        <circle cx="232" cy="678" r="4" /><circle cx="942" cy="672" r="4" /><circle cx="1192" cy="602" r="4" />
      </g>
    </svg>

    <svg class="neural-network" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice">
      <g class="neural-links">
        <path d="M90 250L252 154L404 276L590 124L744 294L932 178L1110 302L1338 176" />
        <path d="M90 250L208 440L404 276L526 492L744 294L864 472L1110 302L1246 488L1338 176" />
        <path d="M208 440L348 646L526 492L696 690L864 472L1048 660L1246 488" />
        <path d="M252 154L208 440M590 124L526 492M932 178L864 472M1110 302L1048 660" />
      </g>
      <g class="neural-nodes">
        <circle cx="90" cy="250" r="5" /><circle cx="252" cy="154" r="4" />
        <circle cx="404" cy="276" r="6" /><circle cx="590" cy="124" r="4" />
        <circle cx="744" cy="294" r="7" /><circle cx="932" cy="178" r="4" />
        <circle cx="1110" cy="302" r="6" /><circle cx="1338" cy="176" r="4" />
        <circle cx="208" cy="440" r="5" /><circle cx="526" cy="492" r="5" />
        <circle cx="864" cy="472" r="6" /><circle cx="1246" cy="488" r="5" />
        <circle cx="348" cy="646" r="4" /><circle cx="696" cy="690" r="6" />
        <circle cx="1048" cy="660" r="5" />
      </g>
    </svg>

    <div class="particle-wave">
      <i v-for="particle in particles" :key="particle.id" :style="particle.style"></i>
    </div>

    <svg class="energy-ribbons" viewBox="0 0 1440 900" preserveAspectRatio="none">
      <g>
        <path d="M-120 724C180 506 370 824 660 638S1110 486 1560 662" />
        <path d="M-120 760C196 542 388 860 682 674S1124 522 1560 698" />
        <path d="M-120 796C212 578 406 896 704 710S1138 558 1560 734" />
        <path d="M-120 832C228 614 424 932 726 746S1152 594 1560 770" />
        <path d="M-120 868C244 650 442 968 748 782S1166 630 1560 806" />
      </g>
    </svg>
    <div class="data-stream-band"></div>
    <div class="scan-beam"></div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{ home?: boolean; quiet?: boolean }>()
const home = computed(() => props.home === true)
const quiet = computed(() => props.quiet === true)
const particleCanvas = ref<HTMLCanvasElement | null>(null)
const paused = ref(document.hidden)

type CanvasParticle = {
  seed: number
  lane: number
  speed: number
  size: number
  alpha: number
  frequency: number
  phase: number
  violet: boolean
  free: boolean
}

const canvasParticles: CanvasParticle[] = Array.from({ length: 240 }, (_, index) => ({
  seed: Math.random(),
  lane: (Math.random() - .5) * 2,
  speed: .55 + Math.random() * 1.7,
  size: .45 + Math.random() * 1.85,
  alpha: .28 + Math.random() * .7,
  frequency: .65 + Math.random() * 1.9,
  phase: Math.random() * Math.PI * 2,
  violet: index % 5 === 0,
  free: index % 7 === 0
}))

let animationFrame = 0
let resizeObserver: ResizeObserver | null = null
let canvasWidth = 0
let canvasHeight = 0
let pixelRatio = 1
let lastFrame = 0
let restartVersion = 0
let disposed = false
const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)')

const resizeCanvas = () => {
  const canvas = particleCanvas.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  canvasWidth = Math.max(1, rect.width)
  canvasHeight = Math.max(1, rect.height)
  pixelRatio = Math.min(window.devicePixelRatio || 1, 1.6)
  canvas.width = Math.round(canvasWidth * pixelRatio)
  canvas.height = Math.round(canvasHeight * pixelRatio)
}

const drawCanvasParticles = (timestamp: number) => {
  if (disposed || document.hidden) return
  const frameInterval = props.quiet ? 1000 / 15 : 1000 / 30
  if (lastFrame && timestamp - lastFrame < frameInterval) {
    animationFrame = requestAnimationFrame(drawCanvasParticles)
    return
  }
  lastFrame = timestamp
  const canvas = particleCanvas.value
  const context = canvas?.getContext('2d')
  if (!canvas || !context || !canvasWidth || !canvasHeight) return

  context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0)
  context.clearRect(0, 0, canvasWidth, canvasHeight)
  context.globalCompositeOperation = 'lighter'

  const areaCount = Math.round((canvasWidth * canvasHeight) / 6000)
  const activeCount = Math.min(
    canvasParticles.length,
    canvasWidth < 720 ? 80 : props.quiet ? 60 : props.home ? Math.max(120, areaCount) : 100
  )
  const time = timestamp * .001
  const motion = reduceMotion.matches ? 0 : (props.quiet ? .13 : 1)

  for (let index = 0; index < activeCount; index += 1) {
    const particle = canvasParticles[index]
    const travel = (particle.seed + time * .018 * particle.speed * motion) % 1.16
    const xNorm = travel - .08
    const x = xNorm * canvasWidth
    let y: number

    if (particle.free) {
      y = canvasHeight * (.12 + particle.seed * .76)
        + Math.sin(time * particle.frequency * motion + particle.phase) * 28
    } else {
      const wave = Math.sin(xNorm * 8.4 + time * .82 * motion + particle.phase) * .075
      const ripple = Math.sin(xNorm * 19 - time * 1.25 * motion + particle.phase) * .018
      y = canvasHeight * (.77 - xNorm * .38 + wave + ripple + particle.lane * .14)
    }

    const twinkle = .55 + Math.sin(time * (1.4 + particle.frequency) * motion + particle.phase) * .35
    const alpha = Math.max(.08, particle.alpha * twinkle)
    const cyan = particle.violet ? [112, 102, 255] : [53, 219, 255]
    const trail = 7 + particle.speed * 10

    context.beginPath()
    context.moveTo(x - trail, y + trail * .22)
    context.lineTo(x, y)
    context.strokeStyle = `rgba(${cyan[0]}, ${cyan[1]}, ${cyan[2]}, ${alpha * .28})`
    context.lineWidth = Math.max(.35, particle.size * .42)
    context.stroke()

    context.beginPath()
    context.arc(x, y, particle.size * (particle.free ? 1.2 : 1), 0, Math.PI * 2)
    context.fillStyle = `rgba(${cyan[0]}, ${cyan[1]}, ${cyan[2]}, ${alpha})`
    context.shadowColor = `rgba(${cyan[0]}, ${cyan[1]}, ${cyan[2]}, .9)`
    context.shadowBlur = 5 + particle.size * 4
    context.fill()
  }

  context.shadowBlur = 0
  context.globalCompositeOperation = 'source-over'
  if (!reduceMotion.matches) animationFrame = requestAnimationFrame(drawCanvasParticles)
}

const restartCanvas = async () => {
  const version = ++restartVersion
  cancelAnimationFrame(animationFrame)
  await nextTick()
  if (disposed || version !== restartVersion || document.hidden) return
  lastFrame = 0
  resizeCanvas()
  drawCanvasParticles(performance.now())
}

const visibilityChanged = () => {
  paused.value = document.hidden
  void restartCanvas()
}

onMounted(() => {
  document.addEventListener('visibilitychange', visibilityChanged)
  reduceMotion.addEventListener('change', restartCanvas)
  resizeObserver = new ResizeObserver(restartCanvas)
  if (particleCanvas.value) resizeObserver.observe(particleCanvas.value)
  restartCanvas()
})

watch(() => [props.home, props.quiet], restartCanvas)

onBeforeUnmount(() => {
  disposed = true
  restartVersion += 1
  document.removeEventListener('visibilitychange', visibilityChanged)
  reduceMotion.removeEventListener('change', restartCanvas)
  cancelAnimationFrame(animationFrame)
  resizeObserver?.disconnect()
})

const particles = Array.from({ length: 96 }, (_, index) => {
  const x = -4 + (index / 95) * 108
  const y = 49 + Math.sin(index * 0.37) * 18 + Math.sin(index * 0.11) * 9
  const size = 2 + (index % 5) * 0.7
  return {
    id: index,
    style: {
      '--x': `${x}%`,
      '--y': `${y}%`,
      '--size': `${size}px`,
      '--delay': `${-(index % 13) * 0.31}s`,
      '--drift': `${10 + (index % 7) * 3}px`
    }
  }
})
</script>

<style scoped>
.tech-backdrop.is-paused :deep(*) { animation-play-state: paused !important; }

.tech-backdrop {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
  background: #030713;
  opacity: .82;
  contain: strict;
}

.tech-backdrop.is-home { opacity: 1; }
.tech-backdrop.is-quiet { opacity: .64; }
.tech-backdrop.is-quiet * { animation: none !important; }

.particle-canvas {
  position: absolute;
  inset: 0;
  z-index: 2;
  width: 100%;
  height: 100%;
  opacity: .7;
  filter: saturate(1.2) contrast(1.08);
}

.tech-backdrop.is-home .particle-canvas { opacity: 1; }
.tech-backdrop.is-quiet .particle-canvas { opacity: .42; }

.tech-aurora {
  position: absolute;
  inset: -20%;
  background:
    radial-gradient(circle at 14% 18%, rgba(22, 199, 255, .17), transparent 25%),
    radial-gradient(circle at 83% 22%, rgba(79, 70, 255, .2), transparent 28%),
    radial-gradient(circle at 48% 91%, rgba(8, 163, 226, .16), transparent 30%);
  animation: aurora-drift 14s ease-in-out infinite alternate;
}

.circuit-traces,
.neural-network,
.energy-ribbons {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.circuit-traces { opacity: .46; }
.circuit { fill: none; stroke: #27d9ff; stroke-width: 1.15; stroke-linecap: round; }
.circuit path { stroke-dasharray: 7 12 88 18; animation: circuit-flow 12s linear infinite; }
.circuit circle { fill: #72efff; filter: drop-shadow(0 0 7px #2bdcff); animation: node-pulse 2.8s ease-in-out infinite; }
.circuit-b { stroke: #6f6bff; }
.circuit-b path { animation-direction: reverse; animation-duration: 15s; }

.neural-network { opacity: .5; animation: network-drift 10s ease-in-out infinite alternate; }
.neural-links { fill: none; stroke: url(#unused); stroke: rgba(75, 164, 255, .3); stroke-width: 1; }
.neural-links path { stroke-dasharray: 4 9; animation: neural-signal 8s linear infinite; }
.neural-nodes circle { fill: #67e8ff; stroke: rgba(111, 111, 255, .85); stroke-width: 2; filter: drop-shadow(0 0 8px #3fbbff); transform-box: fill-box; transform-origin: center; animation: node-pulse 3.2s ease-in-out infinite; }
.neural-nodes circle:nth-child(3n) { fill: #8277ff; animation-delay: -1.1s; }
.neural-nodes circle:nth-child(3n + 2) { animation-delay: -2.2s; }

.particle-wave { position: absolute; inset: 0; filter: drop-shadow(0 0 7px rgba(62, 219, 255, .9)); }
.particle-wave i {
  position: absolute;
  left: var(--x);
  top: var(--y);
  width: var(--size);
  height: var(--size);
  border-radius: 50%;
  background: #71efff;
  box-shadow: 0 0 12px rgba(75, 218, 255, .9);
  animation: particle-float 4.6s ease-in-out var(--delay) infinite alternate;
}
.particle-wave i:nth-child(4n) { background: #867cff; box-shadow: 0 0 14px rgba(103, 91, 255, .95); }

.energy-ribbons { opacity: .58; filter: drop-shadow(0 0 9px rgba(49, 147, 255, .34)); }
.energy-ribbons g { fill: none; stroke: rgba(54, 188, 255, .44); stroke-width: 1.2; }
.energy-ribbons path { stroke-dasharray: 26 13 4 13; animation: ribbon-stream 14s linear infinite; }
.energy-ribbons path:nth-child(2n) { stroke: rgba(103, 92, 255, .48); animation-direction: reverse; animation-duration: 18s; }
.energy-ribbons path:nth-child(3) { stroke-width: 2; }

.data-stream-band {
  position: absolute;
  z-index: 1;
  width: 125%;
  height: 235px;
  left: -18%;
  top: 51%;
  opacity: .58;
  transform: rotate(-13deg);
  transform-origin: center;
  background:
    radial-gradient(circle, rgba(104, 238, 255, .95) 0 1px, transparent 1.8px) 0 0 / 8px 8px,
    radial-gradient(circle, rgba(82, 92, 255, .9) 0 1px, transparent 1.7px) 3px 4px / 11px 11px,
    linear-gradient(180deg, transparent 4%, rgba(39, 211, 255, .12) 34%, rgba(67, 71, 255, .22) 58%, transparent 96%);
  mask-image: linear-gradient(90deg, transparent, #000 10%, #000 88%, transparent), linear-gradient(180deg, transparent, #000 25%, #000 76%, transparent);
  mask-composite: intersect;
  filter: drop-shadow(0 0 17px rgba(38, 191, 255, .55));
  animation: stream-band-drift 11s linear infinite;
}

.scan-beam {
  position: absolute;
  top: -20%;
  bottom: -20%;
  left: -18%;
  width: 18%;
  transform: skewX(-18deg);
  background: linear-gradient(90deg, transparent, rgba(66, 216, 255, .045), transparent);
  animation: scan-across 10s linear infinite;
}

@keyframes aurora-drift { to { transform: translate3d(4%, -3%, 0) scale(1.06); } }
@keyframes circuit-flow { to { stroke-dashoffset: -250; } }
@keyframes neural-signal { to { stroke-dashoffset: -160; } }
@keyframes network-drift { to { transform: translate3d(1.5%, -1%, 0) scale(1.025); } }
@keyframes node-pulse { 50% { opacity: .38; transform: scale(.66); } }
@keyframes particle-float { to { transform: translate3d(var(--drift), -18px, 0) scale(1.45); opacity: .35; } }
@keyframes ribbon-stream { to { stroke-dashoffset: -520; } }
@keyframes scan-across { to { left: 118%; } }
@keyframes stream-band-drift { to { background-position: 160px -40px, -120px 70px, 0 0; } }

@media (max-width: 720px) {
  .tech-backdrop { opacity: .68; }
  .tech-backdrop.is-home { opacity: .88; }
  .neural-network { width: 150%; left: -25%; }
  .energy-ribbons { width: 170%; left: -35%; }
  .data-stream-band { width: 170%; left: -35%; height: 190px; opacity: .36; }
  .circuit-traces { opacity: .34; }
}

@media (prefers-reduced-motion: reduce) {
  .tech-backdrop * { animation-duration: .001ms !important; animation-iteration-count: 1 !important; }
}
</style>
