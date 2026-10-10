/**
 * Sets an element's playbackRate and defends it against sites (e.g. YouTube)
 * whose own `ratechange` listeners revert external rate changes: when a set
 * doesn't stick, re-set it while suppressing the site's listeners via a
 * capture-phase listener + stopImmediatePropagation.
 *
 * Ported from skip-silence's SpeedController, made per-element.
 */
export function createRateSetter(el: HTMLMediaElement) {
  let target = el.playbackRate;
  let blockEvents = false;
  let handlingError = false;
  let listenerAdded = false;

  const forceRate = () => {
    handlingError = true;
    // Rate 0 means the site stopped the media (e.g. not loaded) — leave it be.
    if (el.playbackRate === 0) {
      handlingError = false;
      return;
    }
    blockEvents = true;
    if (!listenerAdded) {
      listenerAdded = true;
      el.addEventListener(
        'ratechange',
        (event) => {
          if (blockEvents) {
            event.stopImmediatePropagation();
          } else if (
            el.playbackRate !== 0 &&
            el.playbackRate === el.defaultPlaybackRate &&
            el.playbackRate !== target
          ) {
            // Site reset the rate back to its default — re-assert ours.
            set(target);
          }
        },
        true,
      );
    }
    setTimeout(() => {
      el.playbackRate = target;
      // Let the blocked ratechange events fire before unblocking.
      setTimeout(() => {
        blockEvents = false;
        handlingError = false;
      }, 1);
    }, 1);
  };

  const set = (rate: number) => {
    target = rate;
    el.playbackRate = rate;
    if (!handlingError) {
      setTimeout(() => {
        if (el.playbackRate !== target) forceRate();
      }, 1);
    }
  };

  return set;
}
