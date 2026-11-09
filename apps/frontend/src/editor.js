import { el, button } from "./dom.js";
import { emptyInput, hasInput, message } from "./state.js";

export class Editor {
  constructor(root, api, submit, cancel) {
    this.root = root;
    this.api = api;
    this.submit = submit;
    this.cancel = cancel;
    this.names = new Map();
    this.token = 0;
    this.locked = false;
  }
  open(input, canCancel) {
    this.token++;
    this.draft = structuredClone(input ?? emptyInput());
    this.root.hidden = false;
    this.root.replaceChildren(
      el(
        "h2",
        { id: "editor-title" },
        canCancel ? "修改这次需求" : "你想听什么？",
      ),
      el("p", { className: "meta" }, "风格、专辑、文字，至少提供一种。"),
    );
    this.form = el("form", {
      onsubmit: async (event) => {
        event.preventDefault();
        if (!this.locked && hasInput(this.draft) && this.form.reportValidity())
          await this.submit(structuredClone(this.draft));
      },
    });
    const columns = el("div", { className: "columns" });
    for (const kind of ["genres", "albums"]) columns.append(this.catalog(kind));
    this.selected = el("div", { "aria-label": "已选输入" });
    this.renderSelected();
    const text = el("textarea", {
      id: "request-text",
      value: this.draft.request_text,
      oninput: (event) => {
        this.draft.request_text = event.target.value;
        event.target.setCustomValidity(
          [...event.target.value].length > 16000
            ? "文字最多 16000 个字符。"
            : "",
        );
        this.updateSubmit();
      },
    });
    const modes = el(
      "fieldset",
      {},
      el("legend", {}, "探索方式"),
      el(
        "div",
        { className: "modes" },
        ...[
          ["familiar", "熟悉"],
          ["explore", "拓展"],
          ["surprise", "惊喜"],
        ].map(([value, label]) =>
          el(
            "label",
            {},
            el("input", {
              type: "radio",
              name: "mode",
              value,
              checked: this.draft.mode === value,
              onchange: () => {
                this.draft.mode = value;
              },
            }),
            ` ${label}`,
          ),
        ),
      ),
    );
    const audience = el("input", {
      id: "audience-min-listeners",
      type: "number",
      min: 1,
      max: 2147483647,
      step: 1,
      value: this.draft.audience_min_listeners ?? "",
      placeholder: "使用默认值",
      oninput: (event) => {
        if (event.target.value === "") delete this.draft.audience_min_listeners;
        else this.draft.audience_min_listeners = event.target.valueAsNumber;
      },
    });
    this.submitButton = el(
      "button",
      { type: "submit", className: "primary" },
      canCancel ? "提交新需求" : "开始发现",
    );
    this.form.append(
      columns,
      this.selected,
      el("label", { htmlFor: "request-text" }, "这次想听的音乐"),
      text,
      modes,
      el("label", { htmlFor: "audience-min-listeners" }, "最低听众数（可选）"),
      audience,
      el(
        "p",
        { className: "meta" },
        "留空使用默认门槛；填写后仅推荐达到该人数的专辑。人数来自 ListenBrainz 样本，不代表全网热度。",
      ),
      el(
        "div",
        { className: "actions" },
        this.submitButton,
        canCancel ? button("取消修改", () => this.cancel()) : null,
      ),
    );
    this.root.append(this.form);
    this.updateSubmit();
    text.focus();
    void this.restoreAlbumNames(this.token);
  }
  async restoreAlbumNames(token) {
    for (const item of this.draft.liked_albums) {
      const id = item.mb_release_group_id;
      if (this.names.has(id)) continue;
      try {
        const value = await this.api.request(
          "GET",
          "/v1/catalog/albums/{mb_release_group_id}",
          { params: { mb_release_group_id: id } },
        );
        if (token !== this.token) return;
        this.names.set(
          id,
          `${value.album.identity.title} — ${value.album.identity.artist_credit}`,
        );
        this.renderSelected();
      } catch {
        // Preserve the original selection and marks even if the catalog is unavailable.
      }
    }
  }
  close() {
    this.token++;
    this.root.hidden = true;
  }
  setLocked(locked) {
    this.locked = locked;
    this.form
      ?.querySelectorAll("button, input, textarea, select")
      .forEach((node) => {
        node.disabled = locked;
      });
    this.updateSubmit();
  }
  updateSubmit() {
    if (this.submitButton)
      this.submitButton.disabled = this.locked || !hasInput(this.draft);
  }
  renderSelected() {
    if (!this.selected) return;
    this.selected.replaceChildren();
    const rows = [
      ...this.draft.liked_genres.map((item) => ({
        item,
        id: item.genre_id,
        list: "liked_genres",
        label: "喜欢风格",
      })),
      ...this.draft.liked_albums.map((item) => ({
        item,
        id: item.mb_release_group_id,
        list: "liked_albums",
        label: "喜欢专辑",
      })),
      ...this.draft.disliked_genre_ids.map((id) => ({
        id,
        list: "disliked_genre_ids",
        label: "不喜欢风格",
      })),
    ];
    for (const row of rows) {
      const name = this.names.get(row.id) ?? row.id;
      const node = el(
        "div",
        { className: "selected-item" },
        el("span", {}, `${row.label} · ${name}`),
      );
      if (row.item) {
        const select = el(
          "select",
          {
            "aria-label": `${name}喜欢程度`,
            onchange: (event) => {
              row.item.strength = event.target.value;
            },
          },
          el("option", { value: "normal" }, "普通喜欢"),
          el("option", { value: "special" }, "特别喜欢"),
        );
        select.value = row.item.strength;
        node.append(
          select,
          el(
            "label",
            {},
            el("input", {
              type: "checkbox",
              checked: row.item.focused,
              onchange: (event) => {
                row.item.focused = event.target.checked;
              },
            }),
            "本次重点",
          ),
        );
      }
      node.append(
        button(
          "移除",
          () => {
            this.draft[row.list] = this.draft[row.list].filter((value) =>
              row.item ? value !== row.item : value !== row.id,
            );
            this.renderSelected();
          },
          { "aria-label": `移除${name}` },
        ),
      );
      this.selected.append(node);
    }
    this.updateSubmit();
    if (this.locked)
      this.selected
        .querySelectorAll("button, input, select")
        .forEach((node) => {
          node.disabled = true;
        });
  }
  catalog(kind) {
    const genres = kind === "genres";
    const container = el(
      "section",
      {},
      el("h3", {}, genres ? "风格偏好" : "喜欢的专辑"),
    );
    const search = el("input", {
      type: "search",
      id: `${kind}-query`,
      placeholder: genres ? "搜索中英文风格" : "搜索艺人或专辑",
    });
    const results = el("div", {
      className: "search-results",
      "aria-live": "polite",
    });
    let sequence = 0;
    const fetchPage = async (query = "", parentId = null, offset = 0) => {
      const current = ++sequence;
      const token = this.token;
      results.replaceChildren(el("p", {}, "正在查询…"));
      try {
        const data = await this.api.request("GET", `/v1/catalog/${kind}`, {
          query: {
            ...(query ? { query } : {}),
            ...(parentId ? { parent_id: parentId } : {}),
            offset: String(offset),
            limit: "20",
          },
        });
        if (current !== sequence || token !== this.token) return;
        results.replaceChildren();
        if (parentId)
          results.append(button("收起子风格，返回根目录", () => fetchPage()));
        if (!data.items.length) results.append(el("p", {}, "没有匹配结果。"));
        for (const item of data.items) {
          const id = genres ? item.genre_id : item.mb_release_group_id;
          const name = genres
            ? item.name
            : `${item.identity.title} — ${item.identity.artist_credit}`;
          this.names.set(id, name);
          const add = (disliked = false) => {
            if (this.locked) return;
            if (disliked) {
              this.draft.liked_genres = this.draft.liked_genres.filter(
                (entry) => entry.genre_id !== id,
              );
              if (!this.draft.disliked_genre_ids.includes(id))
                this.draft.disliked_genre_ids.push(id);
            } else {
              const list = genres ? "liked_genres" : "liked_albums";
              const key = genres ? "genre_id" : "mb_release_group_id";
              if (!genres && this.draft.liked_albums.length >= 10) {
                results.prepend(el("p", {}, "最多选择 10 张专辑。"));
                return;
              }
              if (genres)
                this.draft.disliked_genre_ids =
                  this.draft.disliked_genre_ids.filter((value) => value !== id);
              if (!this.draft[list].some((entry) => entry[key] === id))
                this.draft[list].push({
                  [key]: id,
                  strength: "normal",
                  focused: false,
                });
            }
            this.renderSelected();
            if (genres && item.has_children) void fetchPage("", id);
          };
          results.append(
            el(
              "div",
              { className: "actions" },
              genres
                ? button(name, () => add(), { disabled: this.locked })
                : el("span", {}, name),
              !genres
                ? el(
                    "small",
                    {},
                    `${item.identity.first_release_year ?? "年份未知"} · ${item.identity.release_type ?? "类型未知"}`,
                  )
                : null,
              button(genres ? "喜欢" : "添加", () => add(), {
                disabled: this.locked,
                "aria-label": `${genres ? "喜欢" : "添加"}${name}`,
              }),
              genres
                ? button("不喜欢", () => add(true), {
                    disabled: this.locked,
                    "aria-label": `不喜欢${name}`,
                  })
                : null,
              genres && item.has_children
                ? button("展开", () => fetchPage("", id), {
                    "aria-label": `展开${name}`,
                  })
                : null,
            ),
          );
        }
        if (offset > 0)
          results.append(
            button("上一页", () =>
              fetchPage(query, parentId, Math.max(0, offset - 20)),
            ),
          );
        if (data.next_offset !== null)
          results.append(
            button("下一页", () =>
              fetchPage(query, parentId, data.next_offset),
            ),
          );
        this.renderSelected();
      } catch (error) {
        if (current !== sequence || token !== this.token) return;
        results.replaceChildren(
          el(
            "p",
            {},
            message(error.message, "目录服务暂时不可用，请稍后重试。"),
          ),
          button("重试搜索", () => fetchPage(query, parentId, offset)),
        );
      }
    };
    const runSearch = () => {
      if (genres || search.value.trim()) void fetchPage(search.value.trim());
    };
    search.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        runSearch();
      }
    });
    container.append(
      el("label", { htmlFor: search.id }, genres ? "查找风格" : "查找专辑"),
      el("div", { className: "search-row" }, search, button("搜索", runSearch)),
      results,
    );
    if (genres) void fetchPage();
    return container;
  }
}
