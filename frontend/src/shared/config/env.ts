const rawApiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? ''

/** Backend API origin (no trailing slash). Empty string uses same origin / Vite proxy. */
export const apiBaseUrl = rawApiBaseUrl.replace(/\/$/, '')
