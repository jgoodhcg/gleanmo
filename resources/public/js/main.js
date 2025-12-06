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
  
  // Handle window resize
  window.addEventListener('resize', () => {
    chart.resize();
  });
  
  return chart;
}

function renderEChartFromData(chartElementId, dataElementId) {
  const dataElement = document.getElementById(dataElementId);
  if (!dataElement) {
    console.error('Data element not found:', dataElementId);
    return;
  }
  
  const dataJson = dataElement.textContent || dataElement.innerText;
  
  try {
    const options = JSON.parse(dataJson);
    
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

// Auto-initialize any charts on page load
document.addEventListener('DOMContentLoaded', function() {
  // Check for chart elements with data-chart-data attribute
  const chartElements = document.querySelectorAll('[data-chart-data]');
  chartElements.forEach(element => {
    const dataElementId = element.getAttribute('data-chart-data');
    renderEChartFromData(element.id, dataElementId);
  });
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
  new Choices(select, options);
  select.dataset.choicesInitialized = 'true';
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
