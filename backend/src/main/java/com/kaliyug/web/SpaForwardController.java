package com.kaliyug.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Lets the browser reload client-side routes: they all serve the React app's index.html. */
@Controller
public class SpaForwardController {

    @GetMapping({"/revise", "/library"})
    public String forwardToApp() {
        return "forward:/index.html";
    }
}
