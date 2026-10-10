# https://spring.io/security/cve-2026-47884/
# https://spring.io/security/cve-2026-47890/
# AssetPulse's tested MVC configuration does not render XSLT or SSE view fragments. This
# exception is limited to the packaged dependency in the hosting application;
# other packages, versions, paths and vulnerabilities retain the critical gate.
package trivy

default ignore = false

ignore {
    input.Type == "vulnerability"
    input.VulnerabilityID == {"CVE-2026-47884", "CVE-2026-47890"}[_]
    input.PkgName == "org.springframework:spring-webmvc"
    input.InstalledVersion == "6.2.19"
    input.PkgIdentifier.PURL == "pkg:maven/org.springframework/spring-webmvc@6.2.19"
    input.PkgPath == "app/app.jar/BOOT-INF/lib/spring-webmvc-6.2.19.jar"
}
