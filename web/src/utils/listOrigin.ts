export type ListOrigin = { url: string; params: Record<string,unknown>; path: string; length: number }
const origins = new WeakMap<object,ListOrigin>()
export function rememberLists(value: unknown, origin: Omit<ListOrigin,'path'|'length'>, path='') {
 if (Array.isArray(value)) { const entry={...origin,path,length:value.length}; origins.set(value,entry); for(const row of value)if(row&&typeof row==='object')origins.set(row,entry); return }
 if(value&&typeof value==='object')for(const [key,child] of Object.entries(value))if(key!=='items'&&/^[A-Za-z0-9_]+$/.test(key))rememberLists(child,origin,path?`${path}.${key}`:key)
}
export function listOrigin(rows: unknown[]): ListOrigin | undefined { const match=origins.get(rows)||(rows[0]&&typeof rows[0]==='object'?origins.get(rows[0]):undefined); return match?.length===rows.length?match:undefined }
