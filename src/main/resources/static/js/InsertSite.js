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