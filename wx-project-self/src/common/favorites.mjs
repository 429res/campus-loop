import http from './http.js'

export async function readAllFavorites(options = {}) {
  const size = 100
  let page = 1
  const records = []
  while (true) {
    const result = await http.get('/api/favorites', { page, size }, options)
    const batch = Array.isArray(result?.records) ? result.records : []
    records.push(...batch)
    if (records.length >= Number(result?.total || 0) || batch.length < size) break
    page += 1
  }
  return records
}

export const favoriteIds = records => new Set(records.map(record => String(record.itemId)))

export function setFavorite(itemId, favorited) {
  const url = `/api/items/${itemId}/favorite`
  return favorited
    ? http.put(url, {}, { silent: true, uncertainOnFailure: true })
    : http.delete(url, {}, { silent: true, uncertainOnFailure: true })
}
