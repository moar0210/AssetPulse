import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import App from "./App";
import type { RoleCode } from "./api/session";

const csrfToken = {
  headerName: "X-CSRF-TOKEN",
  token: "csrf-token-value",
} as const;

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function identityFor(roleCode: RoleCode) {
  return {
    userId: "10000000-0000-0000-0000-000000000001",
    displayName: `${roleCode} user`,
    email: `${roleCode.toLowerCase()}@northstar.example`,
    organisation: {
      id: "00000000-0000-0000-0000-000000000001",
      slug: "northstar-operations",
      name: "Northstar Operations",
    },
    role: {
      code: roleCode,
      displayName:
        roleCode === "OPERATIONS_ADMIN"
          ? "Operations Admin"
          : roleCode === "TECHNICIAN"
            ? "Technician"
            : "Viewer",
    },
  } as const;
}

describe("work-order workspace navigation", () => {
  it.each(["OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER"] as const)(
    "mounts Work orders for the %s role",
    async (roleCode) => {
      const identity = identityFor(roleCode);
      const fetchMock = vi.fn<typeof fetch>((input) => {
        const url = String(input);
        if (url === "/api/v1/status") {
          return Promise.resolve(jsonResponse({ status: "available" }));
        }
        if (url === "/api/v1/session/csrf") {
          return Promise.resolve(jsonResponse(csrfToken));
        }
        if (url === "/api/v1/session") {
          return Promise.resolve(jsonResponse(identity));
        }
        if (url === "/api/v1/assets") {
          return Promise.resolve(jsonResponse({ assets: [] }));
        }
        if (url === "/api/v1/work-orders?limit=50") {
          return Promise.resolve(jsonResponse({ workOrders: [], limit: 50 }));
        }
        throw new Error(`Unexpected URL ${url}`);
      });
      vi.stubGlobal("fetch", fetchMock);

      render(<App />);
      const workOrdersNavigation = await screen.findByRole("button", {
        name: "Work orders",
      });
      if (roleCode === "OPERATIONS_ADMIN") {
        expect(
          screen.getByRole("button", { name: "Operations" }),
        ).toBeVisible();
      } else {
        expect(
          screen.queryByRole("button", { name: "Operations" }),
        ).not.toBeInTheDocument();
      }
      fireEvent.click(workOrdersNavigation);

      expect(
        await screen.findByRole("heading", { name: "Work orders" }),
      ).toBeVisible();
      expect(workOrdersNavigation).toHaveAttribute("aria-current", "page");
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/v1/work-orders?limit=50",
        expect.objectContaining({
          method: "GET",
          credentials: "same-origin",
        }),
      );
    },
  );
});
