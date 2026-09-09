package edu.campusloop.web.member.controller;
import edu.campusloop.common.*;
import edu.campusloop.web.member.service.MemberService;
import edu.campusloop.web.item.service.ItemService;
import edu.campusloop.web.item.vo.ItemView;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/members")
public class MemberController {
 private final MemberService members;private final ItemService items;
 public MemberController(MemberService members,ItemService items){this.members=members;this.items=items;}
 @GetMapping("/{id}") public ResultVo<MemberService.Profile> profile(@PathVariable long id){return ResultVo.success(members.profile(id));}
 @GetMapping("/{id}/items") public ResultVo<PageResult<ItemView>> items(@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size){return ResultVo.success(items.memberPage(id,page,size));}
}
