export type Workspace = {
  id: string
  name: string
  createdAt: string
}

export async function fetchCurrentWorkspace(): Promise<Workspace> {
  const response = await fetch('/api/workspace')
  if (!response.ok) {
    throw new Error(`工作空间读取失败（HTTP ${response.status}）`)
  }
  return (await response.json()) as Workspace
}
