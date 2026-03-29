import { useState, useEffect, useCallback } from 'react'
import { bridge } from '../lib/bridge'
import { useRoute } from '../lib/router'
import { useNativeEvent } from '../lib/useNativeEvent'

interface Toast {
  id: string
  message: string
  type: 'success' | 'error' | 'info'
}

export function useToast() {
  const [toasts, setToasts] = useState<Toast[]>([])

  const showToast = useCallback((message: string, type: Toast['type'] = 'info') => {
    const id = Math.random().toString(36).substr(2, 9)
    setToasts(prev => [...prev, { id, message, type }])
    
    // Auto-remove after 3 seconds
    setTimeout(() => {
      setToasts(prev => prev.filter(t => t.id !== id))
    }, 3000)
  }, [])

  const ToastContainer = () => (
    <>
      {toasts.map(toast => (
        <div key={toast.id} className={`toast show ${toast.type}`}>
          {toast.message}
        </div>
      ))}
    </>
  )

  return { showToast, ToastContainer }
}

interface Props {
  onComplete: () => void
}

type SetupPhase = 'platform-select' | 'tool-select' | 'installing' | 'done' | 'error'

interface Platform {
  id: string
  name: string
  icon: string
  desc: string
}

interface Tool {
  id: string
  name: string
  desc: string
  icon: string
  size: string
  time: string
}

const OPTIONAL_TOOLS: Tool[] = [
  { id: 'tmux', name: 'tmux', desc: 'Terminal multiplexer', icon: '🖥️', size: '1MB', time: '10s' },
  { id: 'ttyd', name: 'ttyd', desc: 'Web terminal', icon: '🌐', size: '2MB', time: '15s' },
  { id: 'dufs', name: 'dufs', desc: 'File server', icon: '📁', size: '1MB', time: '10s' },
  { id: 'code-server', name: 'VS Code Server', desc: 'Browser IDE', icon: '📝', size: '150MB', time: '2min' },
  { id: 'claude-code', name: 'Claude Code', desc: 'Anthropic AI CLI', icon: '🤖', size: '50MB', time: '30s' },
  { id: 'gemini-cli', name: 'Gemini CLI', desc: 'Google AI CLI', icon: '🔮', size: '50MB', time: '30s' },
  { id: 'codex-cli', name: 'Codex CLI', desc: 'OpenAI AI CLI', icon: '⚡', size: '50MB', time: '30s' },
  { id: 'opencode', name: 'OpenCode', desc: 'Open source AI assistant', icon: '🔧', size: '80MB', time: '1min' },
]

const TIPS = [
  'Install multiple AI platforms and switch between them anytime.',
  'Setup is one-time. Future launches are instant.',
  'Your AI assistant runs at full speed on device.',
  'All processing happens locally — your data never leaves.',
  'You can add more tools later in Settings.',
]

export function Setup({ onComplete }: Props) {
  const { navigate } = useRoute()
  const { showToast, ToastContainer } = useToast()
  const [phase, setPhase] = useState<SetupPhase>('platform-select')
  const [platforms, setPlatforms] = useState<Platform[]>([])
  const [selectedPlatform, setSelectedPlatform] = useState('')
  const [selectedTools, setSelectedTools] = useState<Set<string>>(new Set())
  const [progress, setProgress] = useState(0)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [tipIndex, setTipIndex] = useState(0)
  const [estimatedTime, setEstimatedTime] = useState(180) // 3 minutes default

  // Load available platforms
  useEffect(() => {
    const data = bridge.callJson<Platform[]>('getAvailablePlatforms')
    if (data) {
      setPlatforms(data)
    } else {
      setPlatforms([
        { id: 'openclaw', name: 'OpenClaw', icon: '/openclaw.svg', desc: 'AI agent platform' },
      ])
    }
  }, [])

  const onProgress = useCallback((data: unknown) => {
    const d = data as { progress?: number; message?: string }
    if (d.progress !== undefined) {
      setProgress(d.progress)
      // Update estimated time based on progress
      if (d.progress > 0) {
        const remaining = Math.max(0, Math.round((1 - d.progress) * 3 * 60))
        setEstimatedTime(remaining)
      }
    }
    if (d.message) {
      setMessage(d.message)
      // Rotate tip every few messages
      if (Math.random() > 0.7) {
        setTipIndex(i => (i + 1) % TIPS.length)
      }
    }
    if (d.progress !== undefined && d.progress >= 1) {
      setPhase('done')
      showToast('Setup complete!', 'success')
    }
  }, [showToast])

  useNativeEvent('setup_progress', onProgress)

  // Listen for errors
  const onError = useCallback((data: { error?: string }) => {
    if (data.error) {
      setError(data.error)
      setPhase('error')
      showToast(`Error: ${data.error}`, 'error')
    }
  }, [showToast])
  useNativeEvent('setup_error', onError)

  function handleSelectPlatform(id: string) {
    setSelectedPlatform(id)
    setPhase('tool-select')
  }

  function toggleTool(id: string) {
    setSelectedTools(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function calculateEstimatedTime(): string {
    const baseTime = 3 * 60 // 3 minutes base
    let toolTime = 0
    selectedTools.forEach(toolId => {
      const tool = OPTIONAL_TOOLS.find(t => t.id === toolId)
      if (tool) {
        const minutes = parseInt(tool.time)
        toolTime += minutes * 60
      }
    })
    const totalSeconds = baseTime + toolTime
    const minutes = Math.ceil(totalSeconds / 60)
    return `${minutes} min`
  }

  function handleStartSetup() {
    // Save tool selections
    const selections: Record<string, boolean> = {}
    OPTIONAL_TOOLS.forEach(t => {
      selections[t.id] = selectedTools.has(t.id)
    })
    bridge.call('saveToolSelections', JSON.stringify(selections))

    // Start bootstrap setup
    setPhase('installing')
    setProgress(0)
    setMessage('Preparing setup...')
    setError('')
    setEstimatedTime(calculateEstimatedTime())
    bridge.call('startSetup')
  }

  function handleRetry() {
    setPhase('installing')
    setProgress(0)
    setMessage('Retrying...')
    setError('')
    bridge.call('startSetup')
  }

  function handleCancel() {
    // Navigate back
    setPhase('tool-select')
    setProgress(0)
    setMessage('')
    setError('')
  }

  // --- Stepper ---
  const currentStep = phase === 'platform-select' ? 0
    : phase === 'tool-select' ? 1
    : phase === 'installing' ? 2 
    : phase === 'done' ? 3
    : 2 // error shows step 3

  const STEPS = ['Platform', 'Tools', 'Setup']

  function renderStepper() {
    return (
      <div className="stepper">
        {STEPS.map((label, i) => (
          <div key={label}>
            {i > 0 && <div className={`step-line${i <= currentStep ? ' done' : ''}`} />}
            <div className={`step${i < currentStep ? ' done' : i === currentStep ? ' active' : ''}`}>
              <span className="step-icon">{i < currentStep ? '✓' : i === currentStep ? '●' : '○'}</span>
              <span>{label}</span>
            </div>
          </div>
        ))}
      </div>
    )
  }

  // --- Platform Select ---
  if (phase === 'platform-select') {
    return (
      <div className="page">
        <ToastContainer />
        <div className="setup-container">
          {renderStepper()}
          <div className="setup-title">Choose your platform</div>

          {platforms.map((p, i) => (
            <div
              key={p.id}
              className="card stagger-item"
              style={{ 
                maxWidth: 340, 
                width: '100%', 
                cursor: 'pointer',
                animationDelay: `${i * 0.1}s`
              }}
              onClick={() => handleSelectPlatform(p.id)}
            >
              <div style={{ fontSize: 32, marginBottom: 8 }}>
                {p.icon.startsWith('/') ? (
                  <img src={p.icon.replace(/^\//, './')} alt={p.name} style={{ width: 40, height: 40 }} />
                ) : p.icon}
              </div>
              <div style={{ fontSize: 18, fontWeight: 600 }}>{p.name}</div>
              <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 4 }}>
                {p.desc}
              </div>
            </div>
          ))}

          <div className="setup-subtitle">More platforms available in Settings.</div>
        </div>
      </div>
    )
  }

  // --- Tool Select ---
  if (phase === 'tool-select') {
    const estimatedTimeStr = calculateEstimatedTime()
    
    return (
      <div className="page">
        <ToastContainer />
        <div className="setup-container" style={{ justifyContent: 'flex-start', paddingTop: 48 }}>
          {renderStepper()}

          <div className="setup-title" style={{ fontSize: 22 }}>Optional Tools</div>
          <div className="setup-subtitle">
            Select tools to install alongside {selectedPlatform}.
            <br />
            <strong>Estimated time: {estimatedTimeStr}</strong>
          </div>

          <div style={{ width: '100%', maxWidth: 360 }}>
            {OPTIONAL_TOOLS.map((tool, i) => {
              const isSelected = selectedTools.has(tool.id)
              return (
                <div
                  key={tool.id}
                  className="card stagger-item"
                  style={{ 
                    cursor: 'pointer', 
                    marginBottom: 8,
                    animationDelay: `${i * 0.05}s`
                  }}
                  onClick={() => toggleTool(tool.id)}
                >
                  <div className="card-row">
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12, flex: 1 }}>
                      <span style={{ fontSize: 24 }}>{tool.icon}</span>
                      <div className="card-content">
                        <div className="card-label">{tool.name}</div>
                        <div className="card-desc">
                          {tool.desc} • {tool.size}
                        </div>
                      </div>
                    </div>
                    <div className={`toggle-switch ${isSelected ? 'active' : ''}`} />
                  </div>
                </div>
              )
            })}
          </div>

          <button className="btn btn-primary" onClick={handleStartSetup} style={{ marginTop: 8 }}>
            Start Setup ({estimatedTimeStr})
          </button>
        </div>
      </div>
    )
  }

  // --- Installing ---
  if (phase === 'installing') {
    const pct = Math.round(progress * 100)
    const minutes = Math.floor(estimatedTime / 60)
    const seconds = estimatedTime % 60
    
    return (
      <div className="page">
        <ToastContainer />
        <div className="setup-container">
          {renderStepper()}
          <div className="setup-title">Setting up...</div>

          <div style={{ width: '100%', maxWidth: 320 }}>
            <div className="progress-bar">
              <div className="progress-fill" style={{ width: `${pct}%` }} />
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8 }}>
              <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{pct}%</div>
              <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                ~{minutes}:{seconds.toString().padStart(2, '0')} remaining
              </div>
            </div>
            <div style={{ textAlign: 'center', fontSize: 12, color: 'var(--text-secondary)', marginTop: 8 }}>
              {message}
            </div>
          </div>

          <button className="btn btn-secondary btn-small" onClick={handleCancel}>
            Cancel
          </button>

          <div className="tip-card stagger-item">💡 {TIPS[tipIndex]}</div>
        </div>
      </div>
    )
  }

  // --- Error ---
  if (phase === 'error') {
    return (
      <div className="page">
        <ToastContainer />
        <div className="setup-container">
          {renderStepper()}
          <div className="error-container">
            <div className="error-icon">❌</div>
            <div className="error-message">{error}</div>
            <div style={{ display: 'flex', gap: 12 }}>
              <button className="btn btn-primary" onClick={handleRetry}>
                Retry
              </button>
              <button className="btn btn-secondary" onClick={handleCancel}>
                Back
              </button>
            </div>
          </div>
        </div>
      </div>
    )
  }

  // --- Done ---
  return (
    <div className="page">
      <ToastContainer />
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-logo">✅</div>
        <div className="setup-title">You're all set!</div>
        <div className="setup-subtitle">
          The terminal will now install runtime components and your selected tools.
        </div>

        <button className="btn btn-primary" onClick={() => {
          bridge.call('showTerminal')
          onComplete()
        }}>
          Open Terminal
        </button>
      </div>
    </div>
  )
}
