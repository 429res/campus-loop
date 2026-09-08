package edu.campusloop.web.demand.service;

import edu.campusloop.common.PageResult;
import edu.campusloop.web.demand.dto.*;
import edu.campusloop.web.demand.vo.*;

public interface DemandService {
    DemandView create(long ownerId, CreateDemandRequest request);
    PageResult<DemandView> page(long ownerId, int page, int size, String status);
    DemandView detail(long ownerId, long id);
    DemandView patch(long ownerId, long id, PatchDemandRequest request);
    DemandView changeStatus(long ownerId, long id, DemandStatusRequest request);
    DeletedDemandView delete(long ownerId, long id, Integer version);
    PageResult<OfferedItemView> offerableItems(long ownerId, int page, int size);
}
