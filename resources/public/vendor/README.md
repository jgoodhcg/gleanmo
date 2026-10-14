# Vendored front-end libraries

Served from this app rather than a CDN, so the code every page runs is the
code in git: an upgrade is a reviewable diff, and no third party can change
it underneath us. Each directory carries its version in its name, which also
busts browser caches when it changes. Licenses sit beside each library.

| Library    | Version | Source                                                        | License    |
|------------|---------|---------------------------------------------------------------|------------|
| Choices.js | 11.2.4  | `cdn.jsdelivr.net/npm/choices.js@11.2.4/public/assets/`       | MIT        |
| htmx       | 1.9.0   | `unpkg.com/htmx.org@1.9.0/dist/htmx.min.js`                    | BSD-2      |
| SortableJS | 1.15.0  | `cdn.jsdelivr.net/npm/sortablejs@1.15.0/Sortable.min.js`      | MIT        |
| ECharts    | 6.0.0   | `cdn.jsdelivr.net/npm/echarts@6.0.0/dist/echarts.min.js`      | Apache-2.0 |

Space Mono lives in `../fonts/space-mono/` (Google Fonts v17, SIL OFL 1.1):
the 400/700 regular and italic faces in the latin, latin-ext and vietnamese
subsets, with `space-mono.css` rewritten to point at the local files.

To upgrade: download the new version's files into a new versioned directory,
update the paths in `src/tech/jgood/gleanmo/ui.clj`, delete the old
directory, and update this table.
