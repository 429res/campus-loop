package edu.campusloop.web.item.service;

import edu.campusloop.common.ApiException;
import edu.campusloop.web.item.entity.Item;
import edu.campusloop.web.item.mapper.ItemMapper;
import org.springframework.stereotype.Component;

/** Invoke after locking the item row, within the caller's write transaction. */
@Component
public class ItemMutationGuard {
    private final ItemMapper items;
    public ItemMutationGuard(ItemMapper items) { this.items = items; }

    public void requireVersion(Item item, Integer version) {
        if (version == null || version < 0) throw new ApiException(400,"需要非负整数 version");
        if (!version.equals(item.getVersion())) throw new ApiException(409,"物品已更新，请刷新后重试");
        if (version == Integer.MAX_VALUE) throw new ApiException(409,"物品版本已达到上限");
    }

    public void requireUnoccupied(Item item) {
        // Expired holds still belong to the exchange state machine; never release them here.
        if (items.lockHold(item.getId()) != null || items.activeExchangeReferences(item.getId()) > 0)
            throw new ApiException(409,"物品存在交换占用或进行中的交换，不能执行此操作");
    }
}
