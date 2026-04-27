import { apiGet, apiPut } from './client'

interface SystemPromptResponse {
  prompt: string
  updatedAt: string
}

function fetchSystemPrompt(): Promise<SystemPromptResponse> {
  return apiGet<SystemPromptResponse>('/api/config/system-prompt')
}

function updateSystemPrompt(prompt: string): Promise<SystemPromptResponse> {
  return apiPut<SystemPromptResponse>('/api/config/system-prompt', { prompt })
}

export { fetchSystemPrompt, updateSystemPrompt }
export type { SystemPromptResponse }
