#!/usr/bin/env node
// Opt-in integration harness. Requires the isolated official DSH host + plugin to be running.
// Authentication and the invitation are read/written through private files/stdin, never argv/logs.
import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const authPath = resolve(process.env.DSH_SMOKE_AUTH_FILE ?? join(root, '../dsh-mobile-plugin/.local/integration/auth.json'))
const { origin, cookie } = JSON.parse(await readFile(authPath, 'utf8'))
if (!['localhost', '127.0.0.1', '[::1]'].includes(new URL(origin).hostname)) throw new Error('The smoke host must be local')
const sdk = process.env.ANDROID_SDK_ROOT ?? process.env.ANDROID_HOME ?? join(process.env.HOME, 'Library/Android/sdk')
const adb = join(sdk, 'platform-tools/adb')
const testPackage = 'io.dsh.mobile.transport.test'
const name = `Android Mac smoke ${randomUUID()}`
let approvedDevice

function run(command, args, input, timeoutMillis) {
  return new Promise((resolveRun, reject) => {
    const child = spawn(command, args, { cwd: root, stdio: ['pipe', 'pipe', 'pipe'] })
    const timer = timeoutMillis ? setTimeout(() => child.kill('SIGTERM'), timeoutMillis) : undefined
    let output = ''
    child.stdout.on('data', bytes => { output += bytes })
    child.stderr.on('data', bytes => { output += bytes })
    child.on('error', reject)
    child.on('close', code => {
      clearTimeout(timer)
      code === 0 ? resolveRun(output) : reject(new Error(`Smoke command failed (${code}): ${output.slice(-4000)}`))
    })
    child.stdin.end(input)
  })
}

async function management(method, payload = {}) {
  const response = await fetch(`${origin}/api/mobileAccess/${method}`, {
    method: 'POST', headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify({ type: 'client-request', rpcId: randomUUID(), method: `mobileAccess/${method}`, payload }),
    signal: AbortSignal.timeout(10_000),
  })
  if (!response.ok) throw new Error(`Desktop management HTTP ${response.status}`)
  const envelope = await response.json()
  if (!envelope.result?.ok) throw new Error(`Desktop management rejected ${method}`)
  return envelope.result.value
}

async function files(directory) {
  const output = []
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) output.push(...await files(path))
    else output.push(path)
  }
  return output
}

await run('./gradlew', [':core:transport:assembleAndroidDeviceTest', '--console=plain'])
const candidates = (await files(join(root, 'core/transport/build/outputs/apk'))).filter(path => path.endsWith('.apk'))
if (candidates.length !== 1) throw new Error('Expected exactly one transport instrumentation APK')
await run(adb, ['install', '-r', candidates[0]])
await run(adb, ['shell', 'run-as', testPackage, 'mkdir', '-p', 'files'])
const invitation = await management('startPairing')
const config = JSON.stringify({ pairingUri: invitation.uri, name, workdir: join(dirname(authPath), 'work') })
await run(adb, ['shell', 'run-as', testPackage, 'sh', '-c', '"cat > files/mac-smoke.json"'], config)
await run(adb, ['shell', 'run-as', testPackage, 'rm', '-f', 'files/mac-smoke-result.json'])

const instrument = run(adb, ['shell', 'am', 'instrument', '-w', '-e', 'class', 'io.dsh.mobile.transport.MacPluginSmokeTest', '-e', 'dshMacSmoke', '1', `${testPackage}/androidx.test.runner.AndroidJUnitRunner`], undefined, 150_000)
let finished = false
let deviceOutput = ''
let failure
instrument.then(value => { deviceOutput = value; finished = true }, error => { failure = error; finished = true })
const deadline = Date.now() + 150_000
try {
  while (!finished && Date.now() < deadline) {
    const status = await management('status')
    const pending = status.pending?.find(device => device.name === name)
    if (pending && !approvedDevice) {
      await management('confirmDevice', { requestId: pending.requestId, approved: true })
      approvedDevice = pending.deviceId
    }
    await new Promise(resolveWait => setTimeout(resolveWait, 200))
  }
  if (!finished) throw new Error('Android/Mac smoke timed out')
  if (failure) throw failure
  if (!deviceOutput.includes('OK (1 test)')) throw new Error(`Instrumentation failed: ${deviceOutput.slice(-4000)}`)
  const result = JSON.parse(await run(adb, ['shell', 'run-as', testPackage, 'cat', 'files/mac-smoke-result.json']))
  await mkdir(join(root, '.local'), { recursive: true })
  await writeFile(join(root, '.local/mac-android-smoke-result.json'), JSON.stringify(result, null, 2), { mode: 0o600 })
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
} finally {
  try {
    if (approvedDevice) await management('revokeDevice', { deviceId: approvedDevice })
  } finally {
    await run(adb, ['shell', 'run-as', testPackage, 'rm', '-f', 'files/mac-smoke.json']).catch(() => {})
  }
}
