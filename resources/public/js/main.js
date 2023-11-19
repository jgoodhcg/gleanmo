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
