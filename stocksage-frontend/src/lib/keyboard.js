export function shouldSubmitEnter(event = {}) {
  return event.isComposing !== true
}
