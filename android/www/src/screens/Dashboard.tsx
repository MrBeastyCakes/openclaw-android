import { useState, useEffect, useCallback } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'

interface BootstrapStatus {
  installed: boolean
  prefixPath?: string
}

interface PlatformInfo {
  id: string
  name: string
}

interface GatewayStatus {
  running: boolean
  uptime?: string
  connections?: number
}

const COMMANDS = [
  { label: 'Gateway', cmd: 'openclaw gateway', desc: 'Start the gateway', icon: '▶️' },
  { label: 'Status', cmd: 'openclaw status', desc: 'Show gateway status', icon: '📊' },
  { label: 'Onboard', cmd: 'openclaw onboard', desc: 'Initial setup wizard', icon: '🚀' },
  { label: 'Logs', cmd: 'openclaw logs --follow', desc: 'Follow live logs', icon: '📜' },
]

const MANAGEMENT = [
  { label: 'Update', cmd: 'oa --update', desc: 'Update OpenClaw and all components', icon: '⬆️' },
  { label: 'Install Tools', cmd: 'oa --install', desc: 'Add or remove optional tools', icon: '➕' },
]

export function Dashboard() {
  const [status, setStatus] = useState<BootstrapStatus | null>(null)
  const [platform, setPlatform] = useState<PlatformInfo | null>(null)
  const [runtimeInfo, setRuntimeInfo] = useState<Record<string, string>>({})
  const [gatewayStatus, setGatewayStatus] = useState<GatewayStatus>({ running: false })
  const [recentCommands, setRecentCommands] = useState<string[]>([])
  const [isLoading, setIsLoading] = useState(true)

  function refreshStatus() {
    setIsLoading(true)
    const bs = bridge.callJson<BootstrapStatus>('getBootstrapStatus')
    if (bs) setStatus(bs)

    const ap = bridge.callJson<PlatformInfo>('getActivePlatform')
    if (ap) setPlatform(ap)

    // Check gateway status
    const gs = bridge.callJson<GatewayStatus>('runCommand', 'openclaw status 2>/dev/null | grep -q "running" && echo "{\\"running\\":true}" || echo "{\\"running\\":false}"')
    if (gs) setGatewayStatus(gs)

    const nodeV = bridge.callJson<{ stdout: string }>('runCommand', 'node -v 2>/dev/null')
    const gitV = bridge.callJson<{ stdout: string }>('runCommand', 'git --version 2>/dev/null')
    const ocV = bridge.callJson<{ stdout: string }>('runCommand', 'openclaw --version 2>/dev/null')
    setRuntimeInfo({
      'Node.js': nodeV?.stdout?.trim() || '—',
      'git': gitV?.stdout?.trim()?.replace('git version ', '') || '—',
      'openclaw': ocV?.stdout?.trim() || '—',
    })
    setIsLoading(false)
  }

  useEffect(() => {
    refreshStatus()
    // Poll for gateway status every 5 seconds
    const interval = setInterval(() => {
      const gs = bridge.callJson<GatewayStatus>('runCommand', 'openclaw status 2>/dev/null | grep -q "running" && echo "{\\"running\\":true}" || echo "{\\"running\\":false}"')
      if (gs) setGatewayStatus(gs)
    }, 5000)
    return () => clearInterval(interval)
  }, [])

  // Listen for command completions
  const onCommandOutput = useCallback((data: unknown) => {
    const d = data as { callbackId?: string; data?: string; done?: boolean }
    if (d.done && d.callbackId?.startsWith('cmd_')) {
      setRecentCommands(prev => [d.callbackId!.replace('cmd_', ''), ...prev].slice(0, 5))
    }
  }, [])
  useNativeEvent('command_output', onCommandOutput)

  function runInTerminal(cmd: string) {
    bridge.call('showTerminal')
    setTimeout(() => {
      bridge.call('writeToTerminal', '', cmd)
    }, 300)
    // Track command
    setRecentCommands(prev => [cmd, ...prev].slice(0, 5))
  }

  if (!status?.installed) {
    return (
      <div className="page fade-in">
        <div className="setup-container" style={{ minHeight: 'calc(100vh - 80px)' }}>
          <img src="./openclaw.svg" alt="OpenClaw" style={{ width: 64, height: 64, marginBottom: 4 }} />
          <div className="setup-title">Setup Required</div>
          <div className="setup-subtitle">
            The runtime environment hasn't been set up yet.
          </div>
          <button className="btn btn-primary" onClick={() => {}} style={{ marginTop: 16 }}>
            Start Setup
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="page fade-in">
      {/* Enhanced Platform Header */}
      <div className="gradient-header stagger-item">
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <img src="./openclaw.svg" alt="OpenClaw" style={{ width: 44, height: 44 }} />
            <div>
              <div style={{ fontSize: 22, fontWeight: 700 }}>
                {platform?.name || 'OpenClaw'}
              </div>
              <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 2 }}>
                AI Agent Platform
              </div>
            </div>
          </div>
          
          {/* Gateway Status Indicator */}
          <div className={`gateway-status ${gatewayStatus.running ? '' : 'offline'}`}>
            <span className="pulse" />
            <span>{gatewayStatus.running ? 'Gateway Online' : 'Gateway Offline'}</span>
          </div>
        </div>
      </div>

      {/* Quick Actions Grid */}
      <div className="section-title stagger-item" style={{ animationDelay: '0.05s' }}>Quick Actions</div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 12, marginBottom: 24 }}>
        {COMMANDS.slice(0, 4).map((item, i) => (
          <div
            key={item.cmd}
            className="card stagger-item"
            style={{ 
              cursor: 'pointer', 
              animationDelay: `${0.1 + i * 0.05}s`,
              padding: 16
            }}
            onClick={() => runInTerminal(item.cmd)}
          >
            <div style={{ fontSize: 24, marginBottom: 8 }}>{item.icon}</div>
            <div className="card-label" style={{ fontSize: 15, marginBottom: 4 }}>{item.label}</div>
            <div className="card-desc" style={{ fontSize: 12 }}>{item.desc}</div>
          </div>
        ))}
      </div>

      {/* Recent Commands */}
      {recentCommands.length > 0 && (
        <>
          <div className="section-title stagger-item" style={{ animationDelay: '0.3s' }}>Recent Commands</div>
          <div className="card stagger-item" style={{ animationDelay: '0.35s' }}>
            {recentCommands.map((cmd, i) => (
              <div 
                key={i} 
                className="card-row"
                style={{ 
                  padding: '8px 0',
                  borderTop: i > 0 ? '1px solid var(--border)' : 'none',
                  cursor: 'pointer'
                }}
                onClick={() => runInTerminal(cmd)}
              >
                <span style={{ fontFamily: 'monospace', fontSize: 13, color: 'var(--accent)' }}>${cmd}</span>
              </div>
            ))}
          </div>
        </>
      )}

      {/* Runtime Info */}
      <div className="section-title stagger-item" style={{ animationDelay: '0.4s' }}>Runtime</div>
      <div className="card stagger-item" style={{ animationDelay: '0.45s' }}>
        {isLoading ? (
          <>
            {[1, 2, 3].map(i => (
              <div key={i} className="info-row">
                <div className="skeleton" style={{ width: '30%', height: 16 }} />
                <div className="skeleton" style={{ width: '40%', height: 16 }} />
              </div>
            ))}
          </>
        ) : (
          Object.entries(runtimeInfo).map(([key, val]) => (
            <div className="info-row" key={key}>
              <span className="label">{key}</span>
              <span style={{ fontFamily: 'monospace', fontSize: 13 }}>{val}</span>
            </div>
          ))
        )}
      </div>

      {/* Management */}
      <div className="section-title stagger-item" style={{ animationDelay: '0.5s' }}>Management</div>
      <div className="card stagger-item" style={{ animationDelay: '0.55s' }}>
        {MANAGEMENT.map((item, i) => (
          <div
            key={item.cmd}
            className="card-row"
            style={{ 
              borderTop: i > 0 ? '1px solid var(--border)' : 'none', 
              padding: '12px 0' 
            }}
            onClick={() => runInTerminal(item.cmd)}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flex: 1 }}>
              <span style={{ fontSize: 20 }}>{item.icon}</span>
              <div className="card-content">
                <div className="card-label">{item.label}</div>
                <div className="card-desc">{item.desc}</div>
              </div>
            </div>
            <div className="card-chevron">›</div>
          </div>
        ))}
      </div>
    </div>
  )
}
