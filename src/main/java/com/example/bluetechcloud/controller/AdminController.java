package com.example.bluetechcloud.controller;

import com.example.bluetechcloud.model.UserDTO;
import com.example.bluetechcloud.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserService userService;

    public AdminController(UserService userService) {
        this.userService = userService;
    }

    private UserDTO checkLogin(HttpSession session) {
        return (UserDTO) session.getAttribute("user");
    }

    private boolean isAdmin(UserDTO user) {
        return user != null
                && user.getRole() != null
                && user.getRole().equalsIgnoreCase("ADMIN");
    }

    @GetMapping
    public String adminPage(HttpSession session, Model model) {

        UserDTO loginUser = checkLogin(session);

        if (!isAdmin(loginUser)) {
            return "redirect:/loginOk";
        }

        model.addAttribute("userCount", userService.getUserCount());
        model.addAttribute("users", userService.getAdminUserList());

        return "admin";
    }

    @PostMapping("/users")
    public String createUser(@RequestParam String username,
                             @RequestParam String password,
                             @RequestParam String name,
                             @RequestParam String role,
                             HttpSession session) {

        UserDTO loginUser = checkLogin(session);

        if (!isAdmin(loginUser)) {
            return "redirect:/loginOk";
        }

        userService.createUser(username, password, name, role);

        return "redirect:/admin";
    }

    @PostMapping("/users/{id}/update")
    public String updateUser(@PathVariable Long id,
                             @RequestParam String username,
                             @RequestParam String name,
                             @RequestParam String role,
                             HttpSession session) {

        UserDTO loginUser = checkLogin(session);

        if (!isAdmin(loginUser)) {
            return "redirect:/loginOk";
        }

        userService.updateUser(id, username, name, role);

        return "redirect:/admin?result=updated";
    }

    @PostMapping("/users/{id}/password")
    public String updatePassword(@PathVariable Long id,
                                 @RequestParam String password,
                                 HttpSession session) {

        UserDTO loginUser = checkLogin(session);

        if (!isAdmin(loginUser)) {
            return "redirect:/loginOk";
        }

        userService.updatePassword(id, password);

        return "redirect:/admin";
    }

    @PostMapping("/users/{id}/delete")
    public String deleteUser(@PathVariable Long id,
                             HttpSession session) {

        UserDTO loginUser = checkLogin(session);

        if (!isAdmin(loginUser)) {
            return "redirect:/loginOk";
        }

        userService.deleteUser(id);

        return "redirect:/admin";
    }
}