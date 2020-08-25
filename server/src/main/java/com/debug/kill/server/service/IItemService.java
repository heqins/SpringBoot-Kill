package com.debug.kill.server.service;

import com.debug.kill.model.entity.ItemKill;
import org.springframework.stereotype.Service;

import java.util.List;

public interface IItemService {
    List<ItemKill> getKillItems() throws Exception;

    ItemKill getKillDetail(Integer id) throws Exception;
}
