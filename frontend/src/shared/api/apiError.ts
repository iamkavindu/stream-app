import type { ProblemDetail } from '@/shared/api/problemDetail'

export class ApiError extends Error {
  readonly problem?: ProblemDetail
  readonly status?: number

  constructor(message: string, problem?: ProblemDetail, status?: number) {
    super(message)
    this.name = 'ApiError'
    this.problem = problem
    this.status = status
  }
}

export async function readApiError(response: Response): Promise<ApiError> {
  const contentType = response.headers.get('content-type') ?? ''
  let problem: ProblemDetail | undefined

  if (contentType.includes('json')) {
    try {
      problem = (await response.json()) as ProblemDetail
    } catch {
      // Response body was not JSON.
    }
  }

  const message =
    problem?.detail?.trim() ||
    problem?.title?.trim() ||
    `Request failed (${response.status})`

  return new ApiError(message, problem, response.status)
}

export function getErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message
  }
  if (error instanceof Error) {
    return error.message
  }
  return 'An unexpected error occurred.'
}
