$(document).ready(function () {

    const savedId = localStorage.getItem("savedLoginId");
    const saveIdChecked = localStorage.getItem("saveIdChecked");
    const autoLoginChecked = localStorage.getItem("autoLoginChecked");

    if (saveIdChecked === "true" && savedId) {
        $("#id").val(savedId);
        $("#saveId").prop("checked", true);
    }

    if (autoLoginChecked === "true") {
        $("#autoLogin").prop("checked", true);

        if (savedId) {
            $("#id").val(savedId);
        }
    }

    $("#loginForm").on("submit", function (event) {

        event.preventDefault();

        const id = $("#id").val().trim();
        const password = $("#password").val().trim();
        const saveId = $("#saveId").is(":checked");
        const autoLogin = $("#autoLogin").is(":checked");

        if (id === "") {
            alert("아이디를 입력해주세요.");
            return false;
        } else if (password === "") {
            alert("비밀번호를 입력해주세요.");
            return false;
        }

        if (saveId || autoLogin) {
            localStorage.setItem("savedLoginId", id);
            localStorage.setItem("saveIdChecked", saveId ? "true" : "false");
        } else {
            localStorage.removeItem("savedLoginId");
            localStorage.removeItem("saveIdChecked");
        }

        localStorage.setItem("autoLoginChecked", autoLogin ? "true" : "false");

        $.ajax({
            method: "POST",
            url: "/loginCheck",
            data: {
                id: id,
                password: password,
                autoLogin: autoLogin
            },
            dataType: "json",
            success: function (result) {

                if (result === 0) {
                    alert("아이디나 비밀번호가 일치하지 않습니다.");
                    return false;
                }

                location.href = "/loginOk";
            },
            error: function (xhr, status, error) {
                alert(xhr.responseText);
                alert(error);
                return false;
            }
        });
    });
});