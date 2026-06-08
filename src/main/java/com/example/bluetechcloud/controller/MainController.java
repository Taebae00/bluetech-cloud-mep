package com.example.bluetechcloud.controller;

import com.example.bluetechcloud.entity.*;
import com.example.bluetechcloud.model.*;
import com.example.bluetechcloud.repository.InspectionItemRepo;
import com.example.bluetechcloud.repository.PhotoRepo;
import com.example.bluetechcloud.repository.SiteInspectionItemRepo;
import com.example.bluetechcloud.repository.UserRepo;
import com.example.bluetechcloud.service.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import com.example.bluetechcloud.repository.InspectionSubItemRepo;

@Controller
public class MainController {

    private final UserService userService;
    private final SiteService siteService;
    private final InspectionItemService inspectionItemService;
    private final InspectionResultService inspectionResultService;
    private final UserRepo userRepo;
    private final PhotoRepo photoRepo;
    private final SitePhotoService sitePhotoService;
    private final InspectionItemRepo inspectionItemRepo;
    private final InspectionSubItemRepo inspectionSubItemRepo;
    private final SiteInspectionItemRepo siteInspectionItemRepo;
    private final ExcelReportService  excelReportService;

    public MainController(UserService userService, SiteService siteService,
                          InspectionItemService inspectionItemService,
                          InspectionResultService inspectionResultService,
                          FileService fileService, InspectionItemRepo inspectionItemRepo, UserRepo userRepo, PhotoRepo photoRepo,
                          SitePhotoService sitePhotoService, InspectionItemRepo inspectionItemRepo1, InspectionSubItemRepo inspectionSubItemRepo, SiteInspectionItemRepo siteInspectionItemRepo, ExcelReportService excelReportService) {
        this.userService = userService;
        this.siteService = siteService;
        this.inspectionItemService = inspectionItemService;
        this.inspectionResultService = inspectionResultService;
        this.userRepo = userRepo;
        this.photoRepo = photoRepo;
        this.sitePhotoService = sitePhotoService;
        this.inspectionItemRepo = inspectionItemRepo1;
        this.inspectionSubItemRepo = inspectionSubItemRepo;
        this.siteInspectionItemRepo = siteInspectionItemRepo;
        this.excelReportService = excelReportService;
    }

    // 🔥 공통 로그인 체크
    private UserDTO checkLogin(HttpSession session) {
        UserDTO user = (UserDTO) session.getAttribute("user");

        if (user == null) {
            return null;
        }

        return user;
    }

    @GetMapping("/")
    public String Main(){
        return "main";
    }

    @PostMapping("/loginCheck")
    @ResponseBody
    public Object loginCheck(@RequestParam String id,
                             @RequestParam String password,
                             @RequestParam(required = false, defaultValue = "false") boolean autoLogin,
                             HttpSession session,
                             HttpServletResponse response) {

        UserDTO dto = userService.loginCheck(id, password);

        if (dto == null) {
            return 0;
        }

        session.setAttribute("user", dto);

        UserEntity userEntity = userRepo.findByUsername(id);

        if (autoLogin) {
            String token = UUID.randomUUID().toString();
            LocalDateTime expiry = LocalDateTime.now().plusDays(30);

            userEntity.setRememberToken(token);
            userEntity.setRememberTokenExpiry(expiry);
            userRepo.save(userEntity);

            Cookie cookie = new Cookie("remember-me", token);
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            cookie.setMaxAge(60 * 60 * 24 * 30);
            response.addCookie(cookie);
        } else {
            userEntity.setRememberToken(null);
            userEntity.setRememberTokenExpiry(null);
            userRepo.save(userEntity);

            Cookie cookie = new Cookie("remember-me", null);
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            cookie.setMaxAge(0);
            response.addCookie(cookie);
        }

        return dto;
    }

    @GetMapping("/loginOk")
    public String loginOk(HttpSession session, Model model){

        UserDTO user = checkLogin(session);
        if (user == null) {
            return "main";
        }

        List<SiteDTO> list = siteService.getList(user.getId());

        Map<Long, String> siteWorkTypeMap = new HashMap<>();

        for (SiteDTO site : list) {
            List<String> workTypes = inspectionItemRepo.findWorkTypesBySiteId(site.getId());

            String siteWorkType = "성능점검";
            if (workTypes != null && !workTypes.isEmpty()) {
                String wt = workTypes.get(0);
                siteWorkType = (wt == null || wt.isBlank()) ? "현황표" : wt;
            }

            siteWorkTypeMap.put(site.getId(), siteWorkType);
        }

        model.addAttribute("list", list);
        model.addAttribute("siteWorkTypeMap", siteWorkTypeMap);
        model.addAttribute("userName", user.getName());
        model.addAttribute("userRole", user.getRole());

        return "siteList";
    }

    @PostMapping("/site/add")
    @ResponseBody
    public ResponseEntity<?> addSite(@RequestBody SiteCreateRequest request, HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            return ResponseEntity.status(401).body("로그인이 필요합니다.");
        }

        if (request.getSiteName() == null || request.getSiteName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("현장명을 입력해주세요.");
        }

        if (request.getWorkType() == null || request.getWorkType().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("작업유형을 선택해주세요.");
        }

        if (request.getCategories() == null || request.getCategories().isEmpty()) {
            return ResponseEntity.badRequest().body("최소 1개 이상의 항목을 선택해주세요.");
        }

        SiteDTO savedSite = siteService.addSite(request.getSiteName().trim(), user.getId());

        List<InspectionItemEntity> selectedItems =
                inspectionItemRepo.findItemsByWorkTypeAndCategories(
                        request.getWorkType(),
                        request.getCategories()
                );

        List<SiteInspectionItemEntity> mappingList = new ArrayList<>();
        for (InspectionItemEntity item : selectedItems) {
            SiteInspectionItemEntity mapping = new SiteInspectionItemEntity();
            mapping.setSiteId(savedSite.getId());
            mapping.setItemId(item.getId());
            mappingList.add(mapping);
        }

        siteInspectionItemRepo.saveAll(mappingList);

        Map<String, Object> result = new HashMap<>();
        result.put("id", savedSite.getId());
        result.put("site_name", savedSite.getSite_name());
        result.put("work_date", savedSite.getWork_date() != null ? savedSite.getWork_date().toString() : "");
        result.put("created_at", savedSite.getCreated_at() != null ? savedSite.getCreated_at().toString() : "");
        result.put("work_type", request.getWorkType());


        return ResponseEntity.ok(result);
    }

    @GetMapping("/site/write/{siteId}")
    public String writePage(@PathVariable Long siteId, Model model, HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            return "main";
        }

        model.addAttribute("loginUser", user);

        SiteDTO site = siteService.getSite(siteId);
        model.addAttribute("site", site);
        model.addAttribute("siteId", siteId);

        List<SiteInspectionItemEntity> mappingList = siteInspectionItemRepo.findBySiteId(siteId);
        List<Long> itemIds = mappingList.stream()
                .map(SiteInspectionItemEntity::getItemId)
                .toList();

        List<InspectionItemEntity> selectedItemEntities = itemIds.isEmpty()
                ? new ArrayList<>()
                : inspectionItemRepo.findAllByIdInOrderByCategoryOrderAscOrderNoAsc(itemIds);

        String siteWorkType = "성능점검";
        if (!selectedItemEntities.isEmpty()) {
            String wt = selectedItemEntities.get(0).getWorkType();
            siteWorkType = (wt == null || wt.isBlank()) ? "현황표" : wt;
        }

        Map<String, List<InspectionItemDTO>> baseGroupedItems = new LinkedHashMap<>();

        if ("현황표".equals(siteWorkType)) {
            for (InspectionItemEntity entity : selectedItemEntities) {
                InspectionItemDTO dto = new InspectionItemDTO();
                dto.setId(entity.getId());
                dto.setCategory(entity.getCategory());
                dto.setCode(entity.getCode());
                dto.setContent(entity.getCategory());
                dto.setOrder_no(1);
                dto.setCategory_order(entity.getCategoryOrder());

                baseGroupedItems.put(entity.getCategory(), List.of(dto));
            }
        } else {
            for (InspectionItemEntity entity : selectedItemEntities) {
                InspectionItemDTO dto = new InspectionItemDTO();
                dto.setId(entity.getId());
                dto.setCategory(entity.getCategory());
                dto.setCode(entity.getCode());
                dto.setContent(entity.getContent());
                dto.setOrder_no(entity.getOrderNo());
                dto.setCategory_order(entity.getCategoryOrder());

                baseGroupedItems
                        .computeIfAbsent(entity.getCategory(), k -> new ArrayList<>())
                        .add(dto);
            }
        }

        if ("성능점검(서울형)".equals(siteWorkType)) {
            fillSubItems(baseGroupedItems);
        }

        List<InspectionResultDTO> resultList = inspectionResultService.getResultsBySiteId(siteId);

        Map<String, Boolean> completedMap = new HashMap<>();
        Map<String, String> resultValueMap = new HashMap<>();
        Map<String, List<Map<String, Object>>> locationViewMap = new LinkedHashMap<>();

        for (String baseCategory : baseGroupedItems.keySet()) {
            locationViewMap.put(baseCategory, new ArrayList<>());
        }

        Map<String, Set<String>> tempGroupSetMap = new LinkedHashMap<>();
        for (String baseCategory : baseGroupedItems.keySet()) {
            tempGroupSetMap.put(baseCategory, new LinkedHashSet<>());
        }

        for (InspectionResultDTO result : resultList) {
            String group = result.getCategory_group();
            if (group == null || group.isBlank()) continue;

            group = group.trim();

            int idx = group.indexOf("_");
            if (idx < 0) continue;

            String baseCategory = group.substring(0, idx).trim();
            String locationName = group.substring(idx + 1).trim();

            if (!tempGroupSetMap.containsKey(baseCategory)) continue;
            if (locationName.isBlank()) continue;

            String key = group + "_" + result.getItem_id();
            String resultValue = result.getResult() == null ? "" : result.getResult().trim();
            String memoValue = result.getMemo() == null ? "" : result.getMemo().trim();

            boolean hasPhoto = !photoRepo.findByResultId(result.getId()).isEmpty();
            boolean hasMemo = !memoValue.isBlank();

            String normalizedResult = resultValue;
            if (!"해당사항없음".equals(resultValue) && (hasPhoto || hasMemo)) {
                normalizedResult = "작성";
            }

            if ("작성".equals(normalizedResult) || "해당사항없음".equals(normalizedResult)) {
                completedMap.put(key, true);
            }

            resultValueMap.put(key, normalizedResult);
            tempGroupSetMap.get(baseCategory).add(group);
        }

        int siteTotalCount = baseGroupedItems.size();
        int siteCompletedCount = 0;

        for (Map.Entry<String, List<InspectionItemDTO>> entry : baseGroupedItems.entrySet()) {
            String baseCategory = entry.getKey();
            List<InspectionItemDTO> items = entry.getValue();
            Set<String> groups = tempGroupSetMap.getOrDefault(baseCategory, new LinkedHashSet<>());

            boolean categoryHasAnyInput = false;

            for (String groupName : groups) {
                int idx = groupName.indexOf("_");
                String locationName = groupName.substring(idx + 1).trim();

                int total = items.size();
                int completed = 0;

                for (InspectionItemDTO item : items) {
                    String key = groupName + "_" + item.getId();
                    if (Boolean.TRUE.equals(completedMap.get(key))) {
                        completed++;
                        categoryHasAnyInput = true;
                    }
                }

                Map<String, Object> locationInfo = new HashMap<>();
                locationInfo.put("groupName", groupName);
                locationInfo.put("locationName", locationName);
                locationInfo.put("completed", completed);
                locationInfo.put("total", total);

                locationViewMap.get(baseCategory).add(locationInfo);
            }

            if (categoryHasAnyInput) {
                siteCompletedCount++;
            }
        }

        model.addAttribute("siteWorkType", siteWorkType);
        model.addAttribute("baseGroupedItems", baseGroupedItems);
        model.addAttribute("locationViewMap", locationViewMap);
        model.addAttribute("completedMap", completedMap);
        model.addAttribute("resultValueMap", resultValueMap);
        model.addAttribute("siteCompletedCount", siteCompletedCount);
        model.addAttribute("siteTotalCount", siteTotalCount);

        return "siteWrite";
    }

    @PostMapping("/category/rename")
    @ResponseBody
    public ResponseEntity<?> renameCategoryGroup(@RequestParam Long siteId,
                                                 @RequestParam String oldCategoryGroup,
                                                 @RequestParam String newLocationName,
                                                 HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            return ResponseEntity.status(401).body("로그인이 필요합니다.");
        }

        try {
            String newGroupName = inspectionResultService.renameCategoryGroup(
                    siteId,
                    oldCategoryGroup,
                    newLocationName
            );

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("newGroupName", newGroupName);
            result.put("newLocationName", newLocationName.trim());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/category/location-list")
    public String getCategoryLocationList(@RequestParam Long siteId,
                                          @RequestParam String categoryName,
                                          Model model,
                                          HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            throw new RuntimeException("로그인이 필요합니다.");
        }

        Map<String, Boolean> completedMap = new HashMap<>();
        Map<String, String> resultValueMap = new HashMap<>();

        List<SiteInspectionItemEntity> mappingList = siteInspectionItemRepo.findBySiteId(siteId);
        List<Long> itemIds = mappingList.stream()
                .map(SiteInspectionItemEntity::getItemId)
                .toList();

        List<InspectionItemEntity> selectedItemEntities = itemIds.isEmpty()
                ? new ArrayList<>()
                : inspectionItemRepo.findAllByIdInOrderByCategoryOrderAscOrderNoAsc(itemIds);

        String siteWorkType = "성능점검";
        if (!selectedItemEntities.isEmpty()) {
            String wt = selectedItemEntities.get(0).getWorkType();
            siteWorkType = (wt == null || wt.isBlank()) ? "현황표" : wt;
        }

        Map<String, List<InspectionItemDTO>> baseGroupedItems = new LinkedHashMap<>();

        if ("현황표".equals(siteWorkType)) {
            for (InspectionItemEntity entity : selectedItemEntities) {
                InspectionItemDTO dto = new InspectionItemDTO();
                dto.setId(entity.getId());
                dto.setCategory(entity.getCategory());
                dto.setCode(entity.getCode());
                dto.setContent(entity.getCategory());
                dto.setOrder_no(1);
                dto.setCategory_order(entity.getCategoryOrder());

                baseGroupedItems.put(entity.getCategory(), List.of(dto));
            }
        } else {
            for (InspectionItemEntity entity : selectedItemEntities) {
                InspectionItemDTO dto = new InspectionItemDTO();
                dto.setId(entity.getId());
                dto.setCategory(entity.getCategory());
                dto.setCode(entity.getCode());
                dto.setContent(entity.getContent());
                dto.setOrder_no(entity.getOrderNo());
                dto.setCategory_order(entity.getCategoryOrder());

                baseGroupedItems
                        .computeIfAbsent(entity.getCategory(), k -> new ArrayList<>())
                        .add(dto);
            }
        }

        // ✅ 성능점검(서울형)은 locationList fragment를 다시 불러올 때도 세부항목을 넣어줘야 함
        if ("성능점검(서울형)".equals(siteWorkType)) {
            fillSubItems(baseGroupedItems);
        }

        List<InspectionResultDTO> resultList = inspectionResultService.getResultsBySiteId(siteId);

        Map<String, List<Map<String, Object>>> locationViewMap = new LinkedHashMap<>();
        for (String baseCategory : baseGroupedItems.keySet()) {
            locationViewMap.put(baseCategory, new ArrayList<>());
        }

        Map<String, Set<String>> tempGroupSetMap = new LinkedHashMap<>();
        for (String baseCategory : baseGroupedItems.keySet()) {
            tempGroupSetMap.put(baseCategory, new LinkedHashSet<>());
        }

        for (InspectionResultDTO result : resultList) {
            String group = result.getCategory_group();
            if (group == null || group.isBlank()) continue;

            group = group.trim();
            int idx = group.indexOf("_");
            if (idx < 0) continue;

            String baseCategory = group.substring(0, idx).trim();
            String locationName = group.substring(idx + 1).trim();

            if (!tempGroupSetMap.containsKey(baseCategory)) continue;
            if (locationName.isBlank()) continue;

            String resultValue = result.getResult() == null ? "" : result.getResult().trim();
            String memoValue = result.getMemo() == null ? "" : result.getMemo().trim();

            boolean hasPhoto = !photoRepo.findByResultId(result.getId()).isEmpty();
            boolean hasMemo = !memoValue.isBlank();

            String normalizedResult = resultValue;
            if (!"해당사항없음".equals(resultValue) && (hasPhoto || hasMemo)) {
                normalizedResult = "작성";
            }

            if ("작성".equals(normalizedResult) || "해당사항없음".equals(normalizedResult)) {
                completedMap.put(group + "_" + result.getItem_id(), true);
            }

            resultValueMap.put(group + "_" + result.getItem_id(), normalizedResult);
            tempGroupSetMap.get(baseCategory).add(group);
        }

        for (Map.Entry<String, List<InspectionItemDTO>> entry : baseGroupedItems.entrySet()) {
            String baseCategory = entry.getKey();
            List<InspectionItemDTO> items = entry.getValue();
            Set<String> groups = tempGroupSetMap.getOrDefault(baseCategory, new LinkedHashSet<>());

            for (String groupName : groups) {
                int idx = groupName.indexOf("_");
                String locationName = groupName.substring(idx + 1).trim();

                int completed = 0;
                for (InspectionItemDTO item : items) {
                    if (Boolean.TRUE.equals(completedMap.get(groupName + "_" + item.getId()))) {
                        completed++;
                    }
                }

                Map<String, Object> locationInfo = new HashMap<>();
                locationInfo.put("groupName", groupName);
                locationInfo.put("locationName", locationName);
                locationInfo.put("completed", completed);
                locationInfo.put("total", items.size());

                locationViewMap.get(baseCategory).add(locationInfo);
            }
        }

        model.addAttribute("entryKey", categoryName);
        model.addAttribute("entryValue", baseGroupedItems.get(categoryName));
        model.addAttribute("locationListData", locationViewMap.get(categoryName));
        model.addAttribute("completedMap", completedMap);
        model.addAttribute("resultValueMap", resultValueMap);
        model.addAttribute("siteWorkType", siteWorkType);

        return "fragments/locationList :: locationList";
    }

    @PostMapping("/inspection/save")
    @ResponseBody
    public ResponseEntity<?> saveInspection(@RequestParam Long siteId,
                                            @RequestParam Long itemId,
                                            @RequestParam String categoryGroup,
                                            @RequestParam String result,
                                            @RequestParam(required = false) String memo,
                                            @RequestParam(required = false) List<MultipartFile> photos,
                                            HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            return ResponseEntity.status(401).body("로그인 필요");
        }

        inspectionResultService.saveInspection(siteId, itemId, categoryGroup, result, memo, photos);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/inspection/photos")
    @ResponseBody
    public Object getPhotos(@RequestParam Long siteId,
                            @RequestParam Long itemId,
                            @RequestParam String categoryGroup,
                            HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            return "login";
        }

        return inspectionResultService.getPhotosBySiteIdAndItemId(siteId, itemId, categoryGroup);
    }

    @PostMapping("/site/delete")
    @ResponseBody
    public String deleteSite(@RequestParam Long siteId,
                             HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            return "login";
        }

        siteService.deleteSite(siteId);
        return "ok";
    }

    @GetMapping("/site/{siteId}/download")
    public void download(@PathVariable Long siteId,
                         HttpServletResponse response,
                         HttpSession session) throws IOException {

        UserDTO user = checkLogin(session);
        if (user == null) {
            response.sendRedirect("/");
            return;
        }

        sitePhotoService.downloadZip(siteId, response);
    }

    @GetMapping("/inspection/detail")
    @ResponseBody
    public Map<String, Object> getInspectionDetail(@RequestParam Long siteId,
                                                   @RequestParam Long itemId,
                                                   @RequestParam String categoryGroup) {

        Map<String, Object> resultMap = new HashMap<>();

        InspectionResultEntity resultEntity =
                inspectionResultService.getInspectionResult(siteId, itemId, categoryGroup);

        if (resultEntity == null) {
            resultMap.put("memo", "");
            resultMap.put("photos", new ArrayList<>());
            return resultMap;
        }

        List<PhotoEntity> photoList = photoRepo.findByResultId(resultEntity.getId());

        List<Map<String, Object>> photos = photoList.stream().map(photo -> {
            Map<String, Object> photoMap = new HashMap<>();
            photoMap.put("id", photo.getId());
            photoMap.put("fileUrl", photo.getFileUrl());
            return photoMap;
        }).toList();

        resultMap.put("result", resultEntity.getResult());
        resultMap.put("memo", resultEntity.getMemo());
        resultMap.put("photos", photos);

        return resultMap;
    }

    @PostMapping("/inspection/photo/delete")
    @ResponseBody
    public ResponseEntity<?> deletePhoto(@RequestParam Long photoId,
                                         HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            return ResponseEntity.status(401).body("로그인 필요");
        }

        inspectionResultService.deletePhoto(photoId);

        return ResponseEntity.ok().build();
    }

    @PostMapping("/inspection-item/custom/add")
    @ResponseBody
    public ResponseEntity<?> addCustomInspectionItem(@RequestParam String categoryName,
                                                     @RequestParam String content,
                                                     HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            return ResponseEntity.status(401).body("로그인 필요");
        }

        InspectionItemDTO dto = inspectionItemService.addCustomItem(categoryName, content);

        return ResponseEntity.ok(dto);
    }

    @PostMapping("/category/add")
    @ResponseBody
    public Map<String, Object> addCategoryGroup(@RequestParam Long siteId,
                                                @RequestParam String templateCategory,
                                                @RequestParam String newCategoryName,
                                                HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            throw new RuntimeException("로그인이 필요합니다.");
        }

        inspectionResultService.addCategoryGroup(siteId, templateCategory, newCategoryName);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("groupName", templateCategory + "_" + newCategoryName.trim());
        result.put("locationName", newCategoryName.trim());

        Map<String, List<InspectionItemDTO>> groupedItems = inspectionItemService.getGroupedItems();
        List<InspectionItemDTO> items = groupedItems.getOrDefault(templateCategory, new ArrayList<>());
        result.put("items", items);

        return result;
    }

    @PostMapping("/category/delete")
    @ResponseBody
    public String deleteCategoryGroup(@RequestParam Long siteId,
                                      @RequestParam String categoryGroup,
                                      HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            return "login";
        }

        inspectionResultService.deleteCategoryGroup(siteId, categoryGroup);
        return "ok";
    }

    @PostMapping("/logout")
    @ResponseBody
    public String logout(HttpSession session, HttpServletResponse response) {

        UserDTO user = (UserDTO) session.getAttribute("user");

        if (user != null) {
            UserEntity userEntity = userRepo.findById(user.getId()).orElse(null);

            if (userEntity != null) {
                userEntity.setRememberToken(null);
                userEntity.setRememberTokenExpiry(null);
                userRepo.save(userEntity);
            }
        }

        session.invalidate();

        Cookie cookie = new Cookie("remember-me", null);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        return "ok";
    }

    @PostMapping("/inspection/reset")
    @ResponseBody
    public ResponseEntity<?> resetInspection(@RequestParam Long siteId,
                                             @RequestParam Long itemId,
                                             @RequestParam String categoryGroup,
                                             @RequestParam String targetResult,
                                             HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            return ResponseEntity.status(401).body("로그인 필요");
        }

        inspectionResultService.resetInspection(siteId, itemId, categoryGroup, targetResult);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/site/categories")
    @ResponseBody
    public List<CategoryDTO> getCategories(@RequestParam String workType, HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            return new ArrayList<>();
        }

        if ("현황표".equals(workType)) {
            return inspectionItemRepo.findCategoryListForStatusTable();
        }

        return inspectionItemRepo.findCategoryListByWorkType(workType);
    }

    @GetMapping("/site/category-edit-data")
    @ResponseBody
    public Map<String, Object> getCategoryEditData(@RequestParam Long siteId, HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            throw new RuntimeException("로그인이 필요합니다.");
        }

        List<SiteInspectionItemEntity> mappingList = siteInspectionItemRepo.findBySiteId(siteId);
        List<Long> itemIds = mappingList.stream()
                .map(SiteInspectionItemEntity::getItemId)
                .toList();

        List<InspectionItemEntity> selectedItemEntities = itemIds.isEmpty()
                ? new ArrayList<>()
                : inspectionItemRepo.findAllByIdInOrderByCategoryOrderAscOrderNoAsc(itemIds);

        String siteWorkType = "성능점검";
        if (!selectedItemEntities.isEmpty()) {
            String wt = selectedItemEntities.get(0).getWorkType();
            siteWorkType = (wt == null || wt.isBlank()) ? "현황표" : wt;
        }

        List<CategoryDTO> allCategories;
        if ("현황표".equals(siteWorkType)) {
            allCategories = inspectionItemRepo.findCategoryListForStatusTable();
        } else {
            allCategories = inspectionItemRepo.findCategoryListByWorkType(siteWorkType);
        }

        List<CategoryDTO> selectedCategories = inspectionItemRepo.findSelectedCategoryListBySiteId(siteId);

        Set<String> selectedSet = new HashSet<>();
        for (CategoryDTO dto : selectedCategories) {
            selectedSet.add(dto.getCategory());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("workType", siteWorkType);
        result.put("allCategories", allCategories);
        result.put("selectedCategories", selectedSet);

        return result;
    }

    @PostMapping("/site/category-edit")
    @ResponseBody
    @Transactional
    public ResponseEntity<?> saveCategoryEdit(@RequestBody SiteCategoryUpdateRequest request,
                                              HttpSession session) {

        UserDTO user = checkLogin(session);
        if (user == null) {
            return ResponseEntity.status(401).body("로그인이 필요합니다.");
        }

        if (request.getSiteId() == null) {
            return ResponseEntity.badRequest().body("siteId가 없습니다.");
        }

        if (request.getCategories() == null || request.getCategories().isEmpty()) {
            return ResponseEntity.badRequest().body("최소 1개 이상 선택해주세요.");
        }

        List<SiteInspectionItemEntity> oldMappings = siteInspectionItemRepo.findBySiteId(request.getSiteId());
        List<Long> oldItemIds = oldMappings.stream()
                .map(SiteInspectionItemEntity::getItemId)
                .toList();

        List<InspectionItemEntity> oldItems = oldItemIds.isEmpty()
                ? new ArrayList<>()
                : inspectionItemRepo.findAllByIdInOrderByCategoryOrderAscOrderNoAsc(oldItemIds);

        String siteWorkType = "성능점검";
        if (!oldItems.isEmpty()) {
            String wt = oldItems.get(0).getWorkType();
            siteWorkType = (wt == null || wt.isBlank()) ? "현황표" : wt;
        }

        siteInspectionItemRepo.deleteBySiteId(request.getSiteId());

        List<InspectionItemEntity> newItems;
        if ("현황표".equals(siteWorkType)) {
            newItems = inspectionItemRepo.findItemsByWorkTypeAndCategories("현황표", request.getCategories());
        } else {
            newItems = inspectionItemRepo.findItemsByWorkTypeAndCategories(siteWorkType, request.getCategories());
        }

        List<SiteInspectionItemEntity> newMappings = new ArrayList<>();
        for (InspectionItemEntity item : newItems) {
            SiteInspectionItemEntity entity = new SiteInspectionItemEntity();
            entity.setSiteId(request.getSiteId());
            entity.setItemId(item.getId());
            newMappings.add(entity);
        }

        siteInspectionItemRepo.saveAll(newMappings);

        return ResponseEntity.ok().build();
    }

    @PostMapping("/sync/site/{siteId}")
    @ResponseBody
    public ResponseEntity<?> syncSite(@PathVariable Long siteId,
                                      @RequestBody SyncRequest request,
                                      HttpSession session) {
        UserDTO user = checkLogin(session);
        if (user == null) {
            return ResponseEntity.status(401).body("로그인 필요");
        }

        Map<String, Long> resultIdMap = inspectionResultService.syncOffline(siteId, request);
        Map<String, Object> response = new HashMap<>();
        response.put("resultIdMap", resultIdMap);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sync/photo/upload")
    @ResponseBody
    public ResponseEntity<?> uploadSyncedPhoto(@RequestParam Long resultId,
                                               @RequestParam MultipartFile photo,
                                               HttpSession session) {
        UserDTO user = checkLogin(session);
        if (user == null) {
            return ResponseEntity.status(401).body("로그인 필요");
        }

        inspectionResultService.saveSyncedPhoto(resultId, photo);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/site/{siteId}/excel")
    public void downloadExcel(@PathVariable Long siteId, HttpServletResponse response) throws IOException {
        excelReportService.downloadPerformanceCheckExcel(siteId, response);
    }

    @PostMapping("/site/{siteId}/download-selected")
    public void downloadSelected(@PathVariable Long siteId,
                                 @RequestBody DownloadSelectedRequest request,
                                 HttpServletResponse response,
                                 HttpSession session) throws IOException {

        UserDTO user = checkLogin(session);
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        List<Long> itemIds = request.getItemIds() == null ? new ArrayList<>() : request.getItemIds();
        List<String> categoryGroups = request.getCategoryGroups() == null ? new ArrayList<>() : request.getCategoryGroups();

        if (itemIds.isEmpty() || categoryGroups.isEmpty() || itemIds.size() != categoryGroups.size()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "다운로드 항목 정보가 올바르지 않습니다.");
            return;
        }

        sitePhotoService.downloadSelectedZip(siteId, itemIds, categoryGroups, response);
    }


    public static class DownloadSelectedRequest {
        private List<Long> itemIds;
        private List<String> categoryGroups;

        public List<Long> getItemIds() {
            return itemIds;
        }

        public void setItemIds(List<Long> itemIds) {
            this.itemIds = itemIds;
        }

        public List<String> getCategoryGroups() {
            return categoryGroups;
        }

        public void setCategoryGroups(List<String> categoryGroups) {
            this.categoryGroups = categoryGroups;
        }
    }

    private void fillSubItems(Map<String, List<InspectionItemDTO>> baseGroupedItems) {
        for (List<InspectionItemDTO> itemList : baseGroupedItems.values()) {
            for (InspectionItemDTO item : itemList) {
                List<InspectionSubItemDTO> subItems =
                        inspectionSubItemRepo.findByItemIdOrderByOrderNoAsc(item.getId())
                                .stream()
                                .map(sub -> {
                                    InspectionSubItemDTO dto = new InspectionSubItemDTO();
                                    dto.setId(sub.getId());
                                    dto.setItem_id(sub.getItemId());
                                    dto.setContent(sub.getContent());
                                    dto.setOrder_no(sub.getOrderNo());
                                    return dto;
                                })
                                .toList();

                item.setSubItems(subItems);
            }
        }
    }

}

