export const ITEM_STATUS_LABELS = Object.freeze({
  AVAILABLE: '可交换',
  RESERVED: '已占用',
  EXCHANGED: '已交换',
})

export const itemStatusLabel = status => ITEM_STATUS_LABELS[status] || status || '状态未知'
export const itemIsAvailable = status => status === 'AVAILABLE'
