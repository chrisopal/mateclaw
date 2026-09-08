import { afterEach, expect, it, vi } from "vitest";
import { createApp, nextTick, type App } from "vue";
import { createPinia, setActivePinia } from "pinia";
import ElementPlus from "element-plus";
import { createI18n } from "vue-i18n";
import en from "@/i18n/locales/en-US";
import { useWorkspaceStore } from "@/stores/useWorkspaceStore";
import Dialog from "../components/OntologyWorkbenchDialog.vue";
import { graphApi } from "../../api/graphApi";
import { semanticRequest } from "../../api/ontologyApi";
const push = vi.hoisted(() => vi.fn());
vi.mock("vue-router", () => ({ useRouter: () => ({ push }) }));
vi.mock("../../api/graphApi", () => ({
  graphApi: { bindingsForOntology: vi.fn() },
}));
vi.mock("../../api/ontologyApi", () => ({ semanticRequest: vi.fn() }));
let app: App, host: HTMLDivElement;
const flush = async () => {
  await new Promise((r) => setTimeout(r, 0));
  await nextTick();
};
afterEach(() => {
  app?.unmount();
  host?.remove();
  vi.clearAllMocks();
});
it("shows knowledge-base names and routes only enabled bindings with exact graph IDs", async () => {
  const pinia = createPinia();
  setActivePinia(pinia);
  useWorkspaceStore().currentWorkspaceId = "1";
  const graphId = "9223372036854775800";
  const binding = {
    graphId,
    workspaceId: "1",
    knowledgeBaseId: "42",
    ontologyRevisionId: "12",
    ontologyVersion: 2,
    enabled: true,
    graphVersion: 1,
    empty: false,
    updatedAt: "",
  };
  vi.mocked(graphApi.bindingsForOntology).mockResolvedValue([
    binding,
    { ...binding, graphId: "43", enabled: false },
  ]);
  vi.mocked(semanticRequest).mockResolvedValue([
    { id: "42", name: "Quality documents" },
  ]);
  host = document.createElement("div");
  document.body.append(host);
  app = createApp(Dialog, {
    ontology: {
      id: "11",
      workspaceId: "1",
      name: "Quality",
      description: "",
      latestVersion: 2,
      latestRevisionId: "12",
      hasDraft: false,
      updatedAt: "",
    },
  });
  app
    .use(pinia)
    .use(ElementPlus)
    .use(createI18n({ legacy: false, locale: "en", messages: { en } }))
    .mount(host);
  await flush();
  await flush();
  expect(document.body.textContent).toContain("Quality documents");
  const buttons = [...document.body.querySelectorAll("button")].filter((b) =>
    b.textContent?.includes("Open knowledge workbench"),
  );
  expect(buttons).toHaveLength(2);
  expect(buttons[1]!.disabled).toBe(true);
  buttons[0]!.click();
  expect(push).toHaveBeenCalledWith({
    name: "SemanticWorkbench",
    params: { graphId },
  });
});
