/** Keep user-login prompts out of admin routes and avoid nesting redirect URLs. */
export function shouldPromptUserLogin(path: string, authQuery: unknown): boolean {
  return !path.startsWith('/admin') && path !== '/login' && path !== '/register'
    && authQuery !== 'login' && authQuery !== 'register' && authQuery !== 'reset'
}
