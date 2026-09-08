export const ITEM_STATUS_LABELS = Object.freeze({
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  REJECTED: '已驳回',
  HIDDEN: '已下架',
  AVAILABLE: '可交换',
  RESERVED: '已占用',
  EXCHANGED: '已交换',
})

export const itemStatusLabel = status => ITEM_STATUS_LABELS[status] || status || '状态未知'
export const itemIsAvailable = status => status === 'AVAILABLE'
