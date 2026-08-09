package io.github.moar0210.assetpulse.status;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public StatusResponse getStatus() {
        return new StatusResponse("available");
    }
}
