// When plain htmx isn't quite enough, you can stick some custom JS here.

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

function renderCalHeatmap() {

  // Retrieve the data from the hidden element
  const dataElement = document.getElementById('cal-heatmap-data');
  const dataJson = dataElement.textContent || dataElement.innerText;
  const data = JSON.parse(dataJson);

  // Ensure data is available
  if (data.length === 0) {
    console.warn('No data available for the heatmap.');
    return;
  }

  // Determine the earliest and latest dates from the data
  const dates = data.map(item => new Date(item.date));
  const earliestDate = new Date(Math.min(...dates));
  const latestDate = new Date(Math.max(...dates));
  const startOfLastMonth = new Date(new Date().getFullYear(), new Date().getMonth() - 1, 1);

  // Compute the start date (first day of the earliest month)
  const startDate = new Date(earliestDate.getFullYear(), earliestDate.getMonth(), 1);

  // Calculate the number of months to display
  const endDate = new Date(latestDate.getFullYear(), latestDate.getMonth(), 1);
  const monthsDiff =
    (endDate.getFullYear() - startDate.getFullYear()) * 12 +
    (endDate.getMonth() - startDate.getMonth()) + 1; // +1 to include the end month

  // Determine the maximum value from the data
  const values = data.map(item => item.value);
  const maxValue = Math.max(1, ...values); // Ensure maxValue is at least 1

  // Compute thresholds for the color scale
  let thresholds;
  if (maxValue <= 3) {
    thresholds = [1, 2, 3];
  } else if (maxValue <= 10) {
    thresholds = [3, 6, 9];
  } else {
    const step = Math.ceil(maxValue / 4);
    thresholds = [step, step * 2, step * 3];
  }

  const bluesColors = ['#c6dbef', '#6baed6', '#2171b5', '#08306b'];

  // Initialize the heatmap with the data
  const cal = new CalHeatmap();
  cal.paint(
    {
      itemSelector: '#cal-heatmap',
      domain: {
        type: 'month',
        gutter: 4,
        label: { text: 'MMM-YY', textAlign: 'start', position: 'top' },
      },
      subDomain: {
        type: 'ghDay',
        radius: 2,
        width: 11,
        height: 11,
        gutter: 4,
      },
      range: monthsDiff,
      // date: { start: startOfLastMonth },
      date: { start: earliestDate },
      scale: {
        color: {
          type: 'threshold',
          range: bluesColors,
          domain: thresholds,
        },
      },
      data: {
        source: data,
        x: 'date',
        y: 'value',
      },
    },
    [
      [
        Tooltip,
        {
          text: function (date, value, dayjsDate) {
            return (
              (value ? value : 'No') +
              ' habit logs on ' +
              dayjsDate.format('dddd, MMMM D, YYYY')
            );
          },
        },
      ],
      [
        LegendLite,
        {
          includeBlank: true,
          itemSelector: '#cal-heatmap-legend',
          radius: 2,
          width: 11,
          height: 11,
          gutter: 4,
        },
      ],
      [
        CalendarLabel,
        {
          width: 30,
          textAlign: 'start',
          text: () => dayjs.weekdaysShort().map((d, i) => (i % 2 === 0 ? '' : d)),
          padding: [25, 0, 0, 0],
        },
      ],
    ]
  );

// Retrieve the container for the heatmap
  const heatmapContainer = document.getElementById('cal-heatmap');

  // Create the button container
  const buttonContainer = document.createElement('div');
  buttonContainer.style.marginTop = '2em';
  buttonContainer.style.marginBottom = '1em';

  const buttonStyle = ' text-blue-500 outline outline-blue-500 outline-2 font-bold py-2 px-4 rounded m-6';

  // Create the "Previous" button
  const prevButton = document.createElement('a');
  prevButton.href = '#';
  prevButton.textContent = '← Previous';
  prevButton.style.marginRight = '1em';
  prevButton.className = buttonStyle;
  prevButton.onclick = function (e) {
    e.preventDefault();
    cal.previous();
  };

  // Create the "Next" button
  const nextButton = document.createElement('a');
  nextButton.href = '#';
  nextButton.textContent = 'Next →';
  nextButton.className = buttonStyle;
  nextButton.onclick = function (e) {
    e.preventDefault();
    cal.next();
  };

  // Append buttons to the button container
  buttonContainer.appendChild(prevButton);
  buttonContainer.appendChild(nextButton);

  // Insert the button container after the heatmap
  heatmapContainer.parentNode.insertBefore(buttonContainer, heatmapContainer.nextSibling);
}
