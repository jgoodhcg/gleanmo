// PWA: Service Worker registration and install prompt
(function() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js');
  }

  var deferredPrompt = null;

  window.addEventListener('beforeinstallprompt', function(e) {
    e.preventDefault();
    deferredPrompt = e;
    showInstallBanner('android');
  });

  window.__pwaPrompt = function() {
    if (deferredPrompt) {
      deferredPrompt.prompt();
      deferredPrompt.userChoice.then(function() {
        deferredPrompt = null;
        hideInstallBanner();
      });
    }
  };

  function isStandalone() {
    return window.matchMedia('(display-mode: standalone)').matches ||
           window.navigator.standalone === true;
  }

  function isDismissed() {
    var ts = localStorage.getItem('pwa-dismiss');
    if (!ts) return false;
    var thirtyDays = 30 * 24 * 60 * 60 * 1000;
    return (Date.now() - parseInt(ts, 10)) < thirtyDays;
  }

  function showInstallBanner(platform) {
    if (isStandalone() || isDismissed()) return;
    var banner = document.getElementById('pwa-install-banner');
    if (!banner) return;
    banner.classList.remove('hidden');
    var androidEl = banner.querySelector('[data-pwa-android]');
    var iosEl = banner.querySelector('[data-pwa-ios]');
    if (androidEl) androidEl.classList.toggle('hidden', platform !== 'android');
    if (iosEl) iosEl.classList.toggle('hidden', platform !== 'ios');
  }

  function hideInstallBanner() {
    var banner = document.getElementById('pwa-install-banner');
    if (banner) banner.classList.add('hidden');
  }

  window.__pwaDismiss = function() {
    localStorage.setItem('pwa-dismiss', Date.now().toString());
    hideInstallBanner();
  };

  // On iOS Safari, no beforeinstallprompt fires — show iOS instructions
  document.addEventListener('DOMContentLoaded', function() {
    if (isStandalone() || isDismissed()) return;
    var isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) && !window.MSStream;
    if (isIOS && !deferredPrompt) {
      showInstallBanner('ios');
    }
  });
})();

function renderEChart(elementId, options) {
  const chartElement = document.getElementById(elementId);
  if (!chartElement) {
    console.error('Chart element not found:', elementId);
    return;
  }
  
  if (typeof echarts === 'undefined') {
    console.error('ECharts library not loaded');
    return;
  }
  
  const chart = echarts.init(chartElement);
  chart.setOption(options);
  chartElement.dataset.chartRendered = 'true';
  
  // Handle window resize
  window.addEventListener('resize', () => {
    chart.resize();
  });
  
  return chart;
}

// Server-built chart options can't carry functions, so they carry a `gleanmo`
// hint instead: `{unit: "h"}` appends a unit to tooltip values, and
// `{format: "hms"}` shows seconds as H:MM:SS on the tooltip and y axis.
function formatHMS(v) {
  if (v == null || isNaN(v)) return '—';
  const s = Math.round(v);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h + ':' + String(m).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
}

function applyGleanmoChartFormat(options) {
  const hint = options.gleanmo;
  if (!hint) return options;
  delete options.gleanmo;
  const value = function (v) {
    if (Array.isArray(v)) v = v[1];
    if (v == null || isNaN(v)) return '—';
    if (hint.format === 'hms') return formatHMS(v);
    const n = (Math.round(v * 10) / 10).toLocaleString('en-US');
    return hint.unit ? n + ' ' + hint.unit : n;
  };
  options.tooltip = Object.assign({}, options.tooltip, { valueFormatter: value });
  if (hint.format === 'hms' && options.yAxis) {
    options.yAxis.axisLabel = Object.assign({}, options.yAxis.axisLabel, {
      formatter: function (v) { return formatHMS(v).replace(/:\d\d$/, ''); }
    });
  }
  return options;
}

function renderEChartFromData(chartElementId, dataElementId) {
  const dataElement = document.getElementById(dataElementId);
  if (!dataElement) {
    console.error('Data element not found:', dataElementId);
    return;
  }

  const dataJson = dataElement.textContent || dataElement.innerText;

  try {
    const options = applyGleanmoChartFormat(JSON.parse(dataJson));
    
    // Add custom styling and tooltip for calendar heatmaps
    if (options.calendar && options.series && options.series[0].type === 'heatmap') {
      
      // Theme constants for easy customization
      const THEME = {
        primaryColor: '#32cd32',        // Neon lime - single color for all activity levels
        backgroundColor: '#0d1117',     // Dark background
        surfaceColor: '#161b22',        // Dark surface
        borderColor: '#30363d',         // Dark border
        textColor: '#c9d1d9',          // Light text
        textSecondary: '#8b949e',       // Secondary text
        accentColor: '#ffd400'          // Gold for highlights/hover
      };
      
      // Single color with opacity variations for activity levels
      const getActivityColor = (count) => {
        if (count === 0) return THEME.surfaceColor;  // No activity
        const opacity = Math.min(0.3 + (count * 0.2), 1.0);  // 0.3 to 1.0 opacity
        return THEME.primaryColor + Math.floor(opacity * 255).toString(16).padStart(2, '0');
      };
      
      // Apply colors to data
      if (options.series[0].data) {
        options.series[0].data = options.series[0].data.map(item => ({
          value: item,
          itemStyle: {
            color: getActivityColor(item[1])
          }
        }));
      }
      
      // Enhanced tooltip
      options.tooltip = {
        formatter: function(params) {
          const date = new Date(params.value[0]);
          const count = params.value[1];
          const entityLabels = params.value[2] || {};  // Now expects an object grouped by relationship type
          const entityType = params.value[3] || 'entity';  // Get entity type from data
          
          const dateStr = date.toLocaleDateString('en-US', { 
            weekday: 'long', 
            year: 'numeric', 
            month: 'long', 
            day: 'numeric' 
          });
          
          // Create readable entity name (convert habit-log to habit logs)
          const readableEntityType = entityType.replace('-', ' ') + (count === 1 ? '' : 's');
          
          let tooltip = `<div style="padding: 8px;"><strong>${dateStr}</strong><br/>${count} ${readableEntityType}`;
          
          // Check if we have grouped relationship data (object) or old format (array)
          if (entityLabels && typeof entityLabels === 'object' && !Array.isArray(entityLabels)) {
            // New grouped format - show each relationship type separately
            const groupKeys = Object.keys(entityLabels);
            if (groupKeys.length > 0) {
              tooltip += '<br/>';
              groupKeys.forEach(groupName => {
                const labels = entityLabels[groupName];
                if (labels && labels.length > 0) {
                  tooltip += `<br/><strong>${groupName}:</strong><br/>`;
                  tooltip += labels.map(label => `• ${label}`).join('<br/>');
                }
              });
            }
          } else if (entityLabels && Array.isArray(entityLabels) && entityLabels.length > 0) {
            // Backward compatibility for old flat array format
            const labelSectionName = entityType.replace('-log', '').replace('-', ' ') + 's';
            const capitalizedLabel = labelSectionName.charAt(0).toUpperCase() + labelSectionName.slice(1);
            tooltip += `<br/><br/><strong>${capitalizedLabel}:</strong><br/>`;
            tooltip += entityLabels.map(label => `• ${label}`).join('<br/>');
          }
          
          tooltip += '</div>';
          return tooltip;
        }
      };
    }
    
    return renderEChart(chartElementId, options);
  } catch (error) {
    console.error('Failed to parse chart options:', error);
    console.error('Raw data was:', dataJson);
  }
}

function initializeECharts(root) {
  const scope = root || document;
  const chartElements = scope.querySelectorAll('[data-chart-data]');
  chartElements.forEach(element => {
    if (element.dataset.chartRendered === 'true') return;
    const dataElementId = element.getAttribute('data-chart-data');
    renderEChartFromData(element.id, dataElementId);
  });
}

// Auto-initialize any charts on page load
document.addEventListener('DOMContentLoaded', function() {
  initializeECharts(document);
});

document.addEventListener('htmx:afterSwap', function(event) {
  initializeECharts(event.detail.elt);
});

function copyToClipboard(text) {
  if (navigator.clipboard) {
    navigator.clipboard.writeText(text)
      .then(() => console.log('Text copied to clipboard'))
      .catch(err => console.error('Failed to copy text: ', err));
  } else {
    // Fallback for browsers that don't support clipboard API
    const textArea = document.createElement('textarea');
    textArea.value = text;
    textArea.style.position = 'fixed';  // Avoid scrolling to bottom
    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();
    
    try {
      const successful = document.execCommand('copy');
      console.log(successful ? 'Text copied to clipboard' : 'Copy failed');
    } catch (err) {
      console.error('Failed to copy text: ', err);
    }
    
    document.body.removeChild(textArea);
  }
}

function setURLParameter(paramName, value) {
  console.log("setting url param: ", paramName, value)
  const url = new URL(window.location);
  // if the value is an empty string or null remove it otherwise set it
  if (value === '' || value === null) {
    url.searchParams.delete(paramName);
  } else {
    url.searchParams.set(paramName, value.toString());
  }
  // keep the url bar in sync
  window.history.pushState({}, null, url.toString());
}

// REMOVED: Old CalHeatmap function - replaced by generic ECharts system


function initializeChoices(select) {
  if (select.dataset.choicesInitialized === 'true') {
    return;
  }
  if (typeof Choices === 'undefined') {
    console.error('Choices.js not loaded');
    return;
  }
  const isMultiple = select.multiple;
  const options = {
    searchEnabled: true,
    shouldSort: false,
    placeholder: !!select.dataset.placeholder,
    placeholderValue: select.dataset.placeholder || 'Select…',
    removeItemButton: select.dataset.removeItem === 'true',
    allowHTML: false,
  };
  if (select.dataset.allowClear === 'true' && !isMultiple) {
    options.allowHTML = false;
    options.removeItemButton = true;
  }
  // When the select offers inline create, point a fruitless search at the
  // affordance below it (plain text — allowHTML stays false).
  if (select.closest('[id^="rel-field-"]')?.querySelector('button[hx-get*="/app/crud/inline/"]')) {
    options.noResultsText = 'No results — close this and use “+ Create” below';
  }
  new Choices(select, options);
  select.dataset.choicesInitialized = 'true';
  wireInlineCreateSearch(select);
}

// Bridge "searched for something that doesn't exist" to "create it".
//
// The inline-create trigger below the select (see crud/forms/inputs.clj)
// normally reads "+ New exercise". While a search matches nothing it becomes
// `+ Create "<typed text>"` and passes that text along as the label prefill,
// so creating costs no extra typing.
//
// Deliberately NOT rendered inside the Choices dropdown: doing that needs a
// custom noResultsText returning markup, which requires allowHTML: true —
// and this codebase sets allowHTML: false everywhere on purpose. Repurposing
// the adjacent button keeps that guarantee and stays keyboard reachable.
function wireInlineCreateSearch(select) {
  const container = select.closest('[id^="rel-field-"]');
  if (!container) return;
  const btn = container.querySelector('button[hx-get*="/app/crud/inline/"]');
  if (!btn) return;

  const defaultText = btn.textContent;
  const baseVals = JSON.parse(btn.getAttribute('hx-vals') || '{}');
  let query = '';

  function apply() {
    const q = query.trim();
    const needle = q.toLowerCase();
    // Compare against the real options; Choices keeps the select in sync.
    const hasMatch =
      !needle ||
      Array.from(select.options).some(
        (o) => o.value && o.text.toLowerCase().includes(needle)
      );

    if (q && !hasMatch) {
      btn.textContent = '+ Create "' + q + '"';
      btn.setAttribute('hx-vals', JSON.stringify(Object.assign({}, baseVals, { label: q })));
    } else {
      btn.textContent = defaultText;
      btn.setAttribute('hx-vals', JSON.stringify(baseVals));
    }
  }

  select.addEventListener('search', function (e) {
    const value = (e.detail && e.detail.value) || '';
    // Ignore empty searches. Choices clears its search box when the dropdown
    // closes, which fires search:"" — treating that as the user clearing the
    // box would revert the affordance exactly when they reach for it.
    if (!value) return;
    query = value;
    apply();
  });
  // Deliberately NOT reset on hideDropdown. The open dropdown covers the
  // button, so the user must close it to reach the affordance — resetting
  // there would revert the label at the exact moment they went to click it.
  // Only an actual selection clears the pending search.
  select.addEventListener('choice', function () { query = ''; apply(); });
}

function initChoicesSelectors(root = document) {
  const selects = root.querySelectorAll('select[data-enhance="choices"]');
  selects.forEach(initializeChoices);
}

document.addEventListener('DOMContentLoaded', function() {
  initChoicesSelectors();
});

document.addEventListener('htmx:afterSettle', function(event) {
  initChoicesSelectors(event.target);
});

// Task Focus filter active state indicators
// Highlights filter fields with non-default values using a cyan border
(function() {
  const FILTER_ACTIVE_CLASS = 'border-neon-cyan';

  // Default values for each filter field (by element ID)
  const FILTER_DEFAULTS = {
    'task-search': '',
    'task-project': '',
    'task-state': 'any',
    'task-domain': '',
    'task-due-on': '',
    'task-due-status': 'any',
    'task-snoozed': 'any',
    'task-sort': 'created-desc'
  };

  function updateFilterBorder(element) {
    if (!element || !element.id) return;

    const defaultValue = FILTER_DEFAULTS[element.id];
    if (defaultValue === undefined) return;

    const currentValue = element.value || '';
    const isActive = currentValue !== defaultValue;

    if (isActive) {
      element.classList.add(FILTER_ACTIVE_CLASS);
    } else {
      element.classList.remove(FILTER_ACTIVE_CLASS);
    }
  }

  function updateAllFilterBorders(root) {
    Object.keys(FILTER_DEFAULTS).forEach(function(id) {
      const element = root.getElementById ? root.getElementById(id) : root.querySelector('#' + id);
      if (element) {
        updateFilterBorder(element);
      }
    });
  }

  function initFilterForm(root) {
    const form = root.getElementById ? root.getElementById('filter-form') : root.querySelector('#filter-form');
    if (!form) return;

    // Update all borders initially
    updateAllFilterBorders(root);

    // Listen for changes on the form
    form.addEventListener('input', function(event) {
      updateFilterBorder(event.target);
    });
    form.addEventListener('change', function(event) {
      updateFilterBorder(event.target);
    });
  }

  // Initialize on page load
  document.addEventListener('DOMContentLoaded', function() {
    initFilterForm(document);
  });

  // Re-initialize after HTMX swaps (in case the form is replaced)
  document.addEventListener('htmx:afterSettle', function(event) {
    updateAllFilterBorders(document);
  });
})();

// Automatic Changed Field Highlighting
//
// Documentation:
// This feature automatically highlights form fields (adds 'border-neon-cyan' class)
// when the user modifies the value away from its original state.
//
// Mechanism:
// 1. Backend: The `render` methods in `inputs.clj` must add a `data-original-value`
//    attribute to every input/select/textarea.
//    - For enums: Use the stringified keyword (e.g. "inbox").
//    - For boolean: Use "true" or "false".
//    - For textarea: Use the string content (preserved newlines).
//    - For Choices.js (select): Use the value of the option (e.g. uuid string).
//
// 2. Frontend: This JS function finds all elements with `data-original-value`.
//    It compares `element.value` (or `checked` state) against this attribute.
//    - Normalizes newlines (\r\n -> \n) for robust textarea comparison.
//    - Handles multi-select sorting.
//
// Edge Cases:
// - Textarea: Standard HTML `<textarea>` does not have a `value` attribute, but
//   JS `.value` property works. We place content in the body but use `data-original-value`
//   for tracking.
// - Choices.js: Hides the original select. We listen for `addItem` and `removeItem`
//   custom events to detect changes. Visual highlighting is applied to the
//   `.choices__inner` wrapper (found via `.closest('.choices')`), not the hidden select.
//
// Highlights form fields where the current value differs from the original value
(function() {
  const CHANGED_CLASS = 'border-neon-cyan';

  function updateFieldStatus(element) {
    if (!element.hasAttribute('data-original-value')) return;

    const originalValue = element.getAttribute('data-original-value');
    let currentValue;

    if (element.type === 'checkbox') {
      currentValue = element.checked.toString();
    } else if (element.multiple) {
      // For multi-selects, we need to sort values to match the backend's sorted original string
      const selected = Array.from(element.selectedOptions).map(opt => opt.value);
      selected.sort();
      currentValue = selected.join(',');
    } else {
      currentValue = element.value;
    }

    // Normalize null/undefined to empty string for comparison
    const normOriginal = (originalValue === 'nil' || originalValue === null ? '' : originalValue).replace(/\r\n/g, '\n');
    const normCurrent = (currentValue === null ? '' : currentValue).replace(/\r\n/g, '\n');

    if (normOriginal !== normCurrent) {
      element.classList.add(CHANGED_CLASS);
      // Also highlight the parent choices wrapper if it exists (for Choices.js)
      const choicesRoot = element.closest('.choices');
      if (choicesRoot) {
          const inner = choicesRoot.querySelector('.choices__inner');
          if (inner) inner.classList.add(CHANGED_CLASS);
      }
    } else {
      element.classList.remove(CHANGED_CLASS);
      const choicesRoot = element.closest('.choices');
      if (choicesRoot) {
          const inner = choicesRoot.querySelector('.choices__inner');
          if (inner) inner.classList.remove(CHANGED_CLASS);
      }
    }
  }

  function initChangedFieldHighlighting(root = document) {
    const inputs = root.querySelectorAll('[data-original-value]');
    
    inputs.forEach(input => {
      // Check initial state
      updateFieldStatus(input);
      
      // Listen for standard changes
      input.addEventListener('input', () => updateFieldStatus(input));
      input.addEventListener('change', () => updateFieldStatus(input));
      
      // Listen for Choices.js specific events (which don't always bubble as 'change')
      input.addEventListener('addItem', () => updateFieldStatus(input));
      input.addEventListener('removeItem', () => updateFieldStatus(input));
    });
  }

  document.addEventListener('DOMContentLoaded', function() {
    initChangedFieldHighlighting();
  });

  document.addEventListener('htmx:afterSettle', function(event) {
    initChangedFieldHighlighting(event.target);
  });
})();

// Generic List Filtering
//
// An input with data-filter-list="<selector>" filters the descendants of the
// target container that carry data-filter-text: rows whose text does not
// contain the input value (case-insensitive substring) get the 'hidden' class.
// Used by the timer workspace's search-to-start list; CRUD lists can reuse it.
//
// Optional data-filter-empty-limit="<n>" keeps a long list compact at rest:
// with an empty query only the first n matching rows show, and an element in
// the container marked data-filter-more reports how many were collapsed.
// Typing any query lifts the cap and searches the whole list.
(function() {
  function applyFilter(input) {
    var container = document.querySelector(input.getAttribute('data-filter-list'));
    if (!container) return;
    var query = input.value.trim().toLowerCase();
    var limit = parseInt(input.getAttribute('data-filter-empty-limit'), 10);
    var capped = query === '' && limit > 0;
    var shown = 0;
    var collapsed = 0;
    container.querySelectorAll('[data-filter-text]').forEach(function(item) {
      var text = (item.getAttribute('data-filter-text') || '').toLowerCase();
      var matches = query === '' || text.indexOf(query) !== -1;
      var overLimit = matches && capped && shown >= limit;
      if (overLimit) collapsed++;
      if (matches && !overLimit) shown++;
      item.classList.toggle('hidden', !matches || overLimit);
    });
    var note = container.querySelector('[data-filter-more]');
    if (note) {
      note.textContent = collapsed > 0
        ? '+' + collapsed + ' more — type to filter'
        : '';
      note.classList.toggle('hidden', collapsed === 0);
    }
  }

  function initFilterInputs(root) {
    var scope = root || document;
    var inputs = scope.querySelectorAll('input[data-filter-list]');
    inputs.forEach(function(input) {
      if (input.dataset.filterInitialized === 'true') return;
      input.addEventListener('input', function() { applyFilter(input); });
      input.dataset.filterInitialized = 'true';
      applyFilter(input);
    });
  }

  document.addEventListener('DOMContentLoaded', function() {
    initFilterInputs(document);
  });

  document.addEventListener('htmx:afterSettle', function(event) {
    initFilterInputs(event.target);
  });
})();

// Task Today — expand/collapse task row details
function toggleTaskRow(rowId) {
  var details = document.querySelector('#' + rowId + ' .task-row-details');
  if (details) details.classList.toggle('hidden');
}

// Sortable Lists - Generic drag-and-drop reordering
//
// Auto-discovers elements with .sortable-list class and initializes
// SortableJS on them. Reads configuration from data attributes:
// - data-sortable-endpoint: URL to POST new order to
//
// Each draggable item should have:
// - .sortable-item class
// - data-sortable-id attribute with unique ID
//
// On drop, POSTs { ids: ["id1", "id2", ...] } to the endpoint.
// The endpoint should return updated HTML for HTMX to swap.
(function() {
  function initSortableList(container) {
    // Skip if already initialized or Sortable not loaded
    if (container.dataset.sortableInitialized === 'true') return;
    if (typeof Sortable === 'undefined') {
      console.warn('SortableJS not loaded, skipping sortable init');
      return;
    }

    const endpoint = container.dataset.sortableEndpoint;
    if (!endpoint) {
      console.warn('Sortable container missing data-sortable-endpoint');
      return;
    }

    new Sortable(container, {
      animation: 150,
      handle: '.drag-handle',
      draggable: '.sortable-item',
      ghostClass: 'sortable-ghost',
      chosenClass: 'sortable-chosen',
      dragClass: 'sortable-drag',
      onEnd: function() {
        const items = container.querySelectorAll('.sortable-item[data-sortable-id]');
        const ids = Array.from(items).map(el => el.dataset.sortableId);

        // Get CSRF token from the page (Biff includes it in hidden inputs)
        const csrfToken = document.querySelector('input[name="__anti-forgery-token"]')?.value;
        const values = { ids: JSON.stringify(ids) };
        if (csrfToken) {
          values['__anti-forgery-token'] = csrfToken;
        }

        // POST to endpoint with HTMX
        htmx.ajax('POST', endpoint, {
          target: container.closest('[id]') || container,
          swap: 'outerHTML',
          values: values
        });
      }
    });

    container.dataset.sortableInitialized = 'true';
  }

  function initAllSortableLists(root = document) {
    const containers = root.querySelectorAll('.sortable-list');
    containers.forEach(initSortableList);
  }

  // Initialize on page load
  document.addEventListener('DOMContentLoaded', function() {
    initAllSortableLists();
  });

  // Re-initialize after HTMX swaps
  document.addEventListener('htmx:afterSettle', function(event) {
    initAllSortableLists(event.target);
    // Also check if the swapped content itself is a sortable list
    if (event.target.classList && event.target.classList.contains('sortable-list')) {
      initSortableList(event.target);
    }
  });
})();

// Double-submit guard: once a form has been submitted, further submits are
// swallowed until the page navigates away.
//
// This exists because timer Start had no feedback on a slow round trip — the
// tap looked like it hadn't registered, a second tap followed, and two timers
// were created. The server-side guard in timer/routes.clj catches duplicates
// that reach it; this one keeps the second tap from being sent at all, and is
// the only half that addresses why the tap happened.
(function() {
  // Long enough to cover a slow round trip, short enough that a form is never
  // stranded if navigation simply never happens (offline, blocked, a response
  // that downloads instead of navigating).
  var BUSY_MS = 8000;

  function isHtmxDriven(form) {
    return form.hasAttribute('hx-post') || form.hasAttribute('hx-get') ||
           form.hasAttribute('data-hx-post') || form.hasAttribute('data-hx-get');
  }

  function unlock(form) {
    delete form.dataset.submitting;
    var busy = form.querySelectorAll('.is-submitting');
    for (var i = 0; i < busy.length; i++) {
      busy[i].classList.remove('is-submitting');
    }
  }

  document.addEventListener('submit', function(event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement)) return;

    // htmx owns its own request lifecycle and offers hx-disabled-elt for this.
    // Locking an htmx form here would strand it: no navigation follows, so
    // nothing would ever clear the lock.
    if (isHtmxDriven(form) || event.defaultPrevented) return;

    // Escape hatch for a form that legitimately submits more than once.
    if (form.hasAttribute('data-allow-resubmit')) return;

    if (form.dataset.submitting === 'true') {
      event.preventDefault();
      return;
    }

    form.dataset.submitting = 'true';

    // Deliberately a class and never `disabled`. A disabled submitter is
    // omitted from the form data, and several buttons here carry their payload
    // as name/value — the timers workspace submits parent-id that way. Class
    // changes cannot affect serialization; `disabled` would break start
    // outright.
    if (event.submitter) event.submitter.classList.add('is-submitting');

    window.setTimeout(function() { unlock(form); }, BUSY_MS);
  });

  // Back/forward cache restores the previous DOM with the lock still set,
  // leaving the form permanently unsubmittable.
  window.addEventListener('pageshow', function(event) {
    if (!event.persisted) return;
    var forms = document.querySelectorAll('form[data-submitting]');
    for (var i = 0; i < forms.length; i++) unlock(forms[i]);
  });
})();

// Goals dashboard table (roadmap/081-goals-dashboard.md)
//
// Measurement and timing filters, text search, and sortable columns over the
// server-rendered rows, which carry `data-kind`, `data-timing`, `data-search`
// and one `data-sort-<column>` value per column (absent values sort last).
// Selecting a row clicks its link, which htmx swaps into the detail card, so
// filter and sort state survive selection.
(function () {
  function sortValue(row, key) {
    var v = row.getAttribute('data-sort-' + key);
    if (v == null || v === '') return null;
    return key === 'label' ? v : parseFloat(v);
  }

  function initGoalsTable(root) {
    var panel = (root || document).querySelector('[data-goals-table]');
    if (!panel || panel.dataset.goalsInit === 'true') return;
    panel.dataset.goalsInit = 'true';
    var tbody = panel.querySelector('tbody');
    var rows = Array.prototype.slice.call(tbody.querySelectorAll('tr[data-goal-row]'));
    var count = panel.querySelector('[data-goals-count]');
    var empty = panel.querySelector('[data-goals-empty]');
    var state = { kind: 'all', timing: 'all', q: '', key: 'label', dir: 1 };

    function apply() {
      var visible = rows.filter(function (r) {
        return (state.kind === 'all' || r.dataset.kind === state.kind) &&
               (state.timing === 'all' || r.dataset.timing === state.timing) &&
               (r.dataset.search || '').indexOf(state.q) !== -1;
      });
      visible.sort(function (a, b) {
        var av = sortValue(a, state.key), bv = sortValue(b, state.key);
        if (av === null) return bv === null ? 0 : 1;
        if (bv === null) return -1;
        return state.dir * (typeof av === 'string' ? av.localeCompare(bv) : av - bv);
      });
      rows.forEach(function (r) { r.hidden = visible.indexOf(r) === -1; });
      visible.forEach(function (r) { tbody.appendChild(r); });
      if (count) count.textContent = visible.length + ' / ' + rows.length;
      if (empty) empty.hidden = visible.length > 0;
      panel.querySelectorAll('th[data-sort-key]').forEach(function (th) {
        var active = th.dataset.sortKey === state.key;
        th.setAttribute('aria-sort', active ? (state.dir === 1 ? 'ascending' : 'descending') : 'none');
        var b = th.querySelector('button');
        b.textContent = b.textContent.replace(/ [↕↑↓]$/, '') + ' ' +
          (active ? (state.dir === 1 ? '↑' : '↓') : '↕');
      });
    }

    function wireGroup(attr, field) {
      var buttons = panel.querySelectorAll('[' + attr + ']');
      buttons.forEach(function (b) {
        b.addEventListener('click', function () {
          state[field] = b.getAttribute(attr);
          buttons.forEach(function (o) { o.setAttribute('aria-pressed', String(o === b)); });
          apply();
        });
      });
    }

    wireGroup('data-goal-kind', 'kind');
    wireGroup('data-goal-timing', 'timing');
    var search = panel.querySelector('[data-goals-search]');
    if (search) {
      search.addEventListener('input', function () {
        state.q = search.value.trim().toLowerCase();
        apply();
      });
    }
    panel.querySelectorAll('th[data-sort-key] button').forEach(function (b) {
      b.addEventListener('click', function () {
        var key = b.closest('th').dataset.sortKey;
        state.dir = state.key === key ? -state.dir : 1;
        state.key = key;
        apply();
      });
    });
    tbody.addEventListener('click', function (e) {
      var row = e.target.closest('tr[data-goal-row]');
      if (!row) return;
      rows.forEach(function (r) { r.setAttribute('aria-selected', String(r === row)); });
      if (!e.target.closest('a, button, form')) {
        var link = row.querySelector('a[data-goal-link]');
        if (link) link.click();
      }
    });
  }

  document.addEventListener('DOMContentLoaded', function () { initGoalsTable(document); });

  // Delegation also handles activity strips replaced by goal selection through HTMX.
  document.addEventListener('focusin', function (e) {
    var cell = e.target.closest('[data-activity-cell]');
    if (!cell) return;
    var panel = cell.closest('[data-goal-activity]');
    if (!panel) return;
    panel.querySelectorAll('[data-activity-cell]').forEach(function (other) {
      other.tabIndex = other === cell ? 0 : -1;
    });
    panel.querySelector('[data-activity-readout]').textContent = cell.getAttribute('aria-label');
  });

  document.addEventListener('keydown', function (e) {
    var cell = e.target.closest('[data-activity-cell]');
    if (!cell || e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
    var panel = cell.closest('[data-goal-activity]');
    if (!panel) return;
    var cells = Array.prototype.slice.call(panel.querySelectorAll('[data-activity-cell]'));
    var index = cells.indexOf(cell);
    switch (e.key) {
      case 'ArrowLeft': index = Math.max(0, index - 1); break;
      case 'ArrowRight': index = Math.min(cells.length - 1, index + 1); break;
      case 'Home': index = 0; break;
      case 'End': index = cells.length - 1; break;
      default: return;
    }
    e.preventDefault();
    cells[index].focus();
  });
})();
