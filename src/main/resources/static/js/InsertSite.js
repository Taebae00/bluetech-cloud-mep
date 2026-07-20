$(function () {
    console.log("InsertSite.js loaded");

    function loadCategories(workType) {
        $.ajax({
            type: "GET",
            url: "/site/categories",
            data: { workType: workType },
            dataType: "json",
            success: function (list) {
                let html = "";

                list.forEach(function (item) {
                    html += `
                        <label class="category-item">
                            <input type="checkbox" name="categoryItem" value="${item.category}" checked>
                            <span>${item.category}</span>
                        </label>
                    `;
                });

                $("#categoryBox").html(html);
            },
            error: function (xhr, status, error) {
                console.log("category load error:", xhr, status, error);
                alert("카테고리 목록을 불러오지 못했습니다.");
            }
        });
    }

    $("#openSiteModal").on("click", function () {
        $("#siteModal").show();
        $("#siteNameInput").val("").focus();

        const workType = $("input[name='workType']:checked").val();
        loadCategories(workType);
    });

    $("#closeSiteModal").on("click", function () {
        $("#siteModal").hide();
    });

    $("#siteModal").on("click", function (e) {
        if (e.target.id === "siteModal") {
            $("#siteModal").hide();
        }
    });

    $(document).on("change", "input[name='workType']", function () {
        loadCategories($(this).val());
    });

    $("#checkAllBtn").on("click", function () {
        $("input[name='categoryItem']").prop("checked", true);
    });

    $("#uncheckAllBtn").on("click", function () {
        $("input[name='categoryItem']").prop("checked", false);
    });

    $("#saveSiteBtn").on("click", function () {
        const siteName = $("#siteNameInput").val().trim();
        const workType = $("input[name='workType']:checked").val();

        const categories = [];
        $("input[name='categoryItem']:checked").each(function () {
            categories.push($(this).val());
        });

        if (siteName === "") {
            alert("현장명을 입력해주세요.");
            return;
        }

        if (!workType) {
            alert("작업유형을 선택해주세요.");
            return;
        }

        if (categories.length === 0) {
            alert("최소 1개 이상의 항목을 선택해주세요.");
            return;
        }

        $.ajax({
            type: "POST",
            url: "/site/add",
            contentType: "application/json",
            data: JSON.stringify({
                siteName: siteName,
                workType: workType,
                categories: categories
            }),
            dataType: "json",
            success: function (result) {
                console.log(result);

                $("#siteModal").hide();
                $("#emptyBox").hide();

                const createdAtText = result.created_at
                    ? result.created_at.replace("T", " ").substring(0, 16)
                    : "";

                const workDateText = result.work_date
                    ? result.work_date.replace("T", " ").substring(0, 10)
                    : "";

                const newHtml = `
    <a class="site-card" href="/site/write/${result.id}">
        <div class="site-name">${result.site_name} 현장 ${result.work_type}</div>
        <div class="site-info">
            <span>작업일자 :</span>
            <span>${workDateText}</span>
        </div>
        <div class="site-info">
            <span>작성일시 :</span>
            <span>${createdAtText}</span>
        </div>
        <div class="go-write">작성하러 가기 →</div>
    </a>
`;

                $("#siteListBox").prepend(newHtml);
                $("#siteNameInput").val("");
                $("#categoryBox").html("");
            },
            error: function (xhr, status, error) {
                console.log("ajax error:", xhr, status, error);
                alert(xhr.responseText || "현장 추가 중 오류가 발생했습니다.");
            }
        });
    });

    $(document).on("click", "#logoutBtn", function () {
        $.ajax({
            type: "POST",
            url: "/logout",
            success: function () {
                alert("로그아웃되었습니다.");
                window.location.href = "/";
            },
            error: function (xhr) {
                console.error(xhr.responseText);
                alert("로그아웃 실패");
            }
        });
    });
});

function openCategoryEditModal() {
    const siteId = $("#siteId").val();

    $.ajax({
        type: "GET",
        url: "/site/category-edit-data",
        data: { siteId: siteId },
        dataType: "json",
        success: function (result) {
            let html = "";
            const selectedSet = new Set(result.selectedCategories || []);

            result.allCategories.forEach(function (item) {
                const checked = selectedSet.has(item.category) ? "checked" : "";

                html += `
                    <label class="category-item">
                        <input type="checkbox" name="editCategoryItem" value="${item.category}" ${checked}>
                        <span>${item.category}</span>
                    </label>
                `;
            });

            $("#categoryEditBox").html(html);
            $("#categoryEditModal").show();
        },
        error: function (xhr) {
            console.error(xhr.responseText);
            alert("점검항목 목록을 불러오지 못했습니다.");
        }
    });
}

function closeCategoryEditModal() {
    $("#categoryEditModal").hide();
}

$(document).on("click", "#editCheckAllBtn", function () {
    $("input[name='editCategoryItem']").prop("checked", true);
});

$(document).on("click", "#editUncheckAllBtn", function () {
    $("input[name='editCategoryItem']").prop("checked", false);
});

function saveCategoryEdit() {
    const siteId = $("#siteId").val();
    const categories = [];

    $("input[name='editCategoryItem']:checked").each(function () {
        categories.push($(this).val());
    });

    if (categories.length === 0) {
        alert("최소 1개 이상 선택해주세요.");
        return;
    }

    $.ajax({
        type: "POST",
        url: "/site/category-edit",
        contentType: "application/json",
        data: JSON.stringify({
            siteId: siteId,
            categories: categories
        }),
        success: function () {
            alert("점검항목이 수정되었습니다.");
            location.reload();
        },
        error: function (xhr) {
            console.error(xhr.responseText);
            alert(xhr.responseText || "점검항목 수정 중 오류가 발생했습니다.");
        }
    });
}

async function openStorageDiagnosis() {
    const modal = document.getElementById("storageDiagnosisModal");
    const text = document.getElementById("storageDiagnosisText");

    if (modal) modal.style.display = "flex";
    if (text) text.textContent = "확인 중...";

    try {
        const photos = await OfflineDB.getAll("draft_photos");
        const results = await OfflineDB.getAll("draft_results");

        const siteIdEl = document.getElementById("siteId");
        const siteId = siteIdEl && siteIdEl.value ? Number(siteIdEl.value) : null;

        const sitePhotos = siteId
            ? photos.filter(p => Number(p.siteId) === siteId)
            : photos;

        const siteResults = siteId
            ? results.filter(r => Number(r.siteId) === siteId)
            : results;

        const scopeText = siteId
            ? `현재 현장 ID: ${siteId}`
            : "전체 로컬 데이터";

        const totalBytes = sitePhotos.reduce((sum, p) => {
            return sum + (p.fileBlob?.size || 0);
        }, 0);

        const pendingPhotos = sitePhotos.filter(p => p.syncStatus === "pending").length;
        const failedPhotos = sitePhotos.filter(p => p.syncStatus === "failed" || p.lastError).length;
        const deletePhotos = sitePhotos.filter(p => p.isDeleted === true).length;
        const memoUpdatePhotos = sitePhotos.filter(p => p.isMemoUpdate === true).length;
        const uploadingPhotos = sitePhotos.filter(p => p.uploading === true).length;

        let usageText = "확인 불가";

        if (navigator.storage && navigator.storage.estimate) {
            const estimate = await navigator.storage.estimate();

            const usageMB = estimate.usage
                ? (estimate.usage / 1024 / 1024).toFixed(1)
                : "0";

            const quotaMB = estimate.quota
                ? (estimate.quota / 1024 / 1024).toFixed(1)
                : "0";

            const percent = estimate.usage && estimate.quota
                ? ((estimate.usage / estimate.quota) * 100).toFixed(1)
                : "0";

            usageText = `${usageMB}MB / ${quotaMB}MB (${percent}%)`;
        }

        const biggest = sitePhotos
            .filter(p => p.fileBlob)
            .sort((a, b) => (b.fileBlob?.size || 0) - (a.fileBlob?.size || 0))
            .slice(0, 5)
            .map((p, i) => {
                const sizeMB = (p.fileBlob.size / 1024 / 1024).toFixed(1);
                return `${i + 1}. ${sizeMB}MB / ${p.syncStatus || "-"} / ${p.createdAt || "-"}`;
            })
            .join("\n");

        text.textContent =
            `${scopeText}

[로컬 데이터]
사진 개수: ${sitePhotos.length}장
점검 결과: ${siteResults.length}건
사진 총 용량: ${(totalBytes / 1024 / 1024).toFixed(1)}MB

[사진 상태]
대기중: ${pendingPhotos}건
실패/오류: ${failedPhotos}건
삭제예약: ${deletePhotos}건
메모수정: ${memoUpdatePhotos}건
업로드중 표시: ${uploadingPhotos}건

[브라우저 저장공간]
${usageText}

[큰 사진 TOP 5]
${biggest || "없음"}`;

    } catch (e) {
        console.error(e);
        if (text) {
            text.textContent = "저장공간 진단 중 오류가 발생했습니다.\n\n" + (e.message || e);
        }
    }
}

function closeStorageDiagnosis() {
    const modal = document.getElementById("storageDiagnosisModal");
    if (modal) modal.style.display = "none";
}
