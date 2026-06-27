/** RFC 7807 Problem Details (Spring `ProblemDetail` JSON). */
export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
}
