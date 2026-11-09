import { useEffect, useState } from "react";
import { coverArt } from "../lib/cover.ts";

const COUNT = 60;

/**
 * A wall of abstract sleeves, like a record shop seen from across the room. Every couple of
 * seconds one sleeve is swapped for another; reduced-motion users get a still wall.
 */
export function Wall() {
  const [seeds, setSeeds] = useState(() =>
    Array.from({ length: COUNT }, (_, index) => `wall-${index}`),
  );
  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    let generation = COUNT;
    const timer = setInterval(() => {
      const index = Math.floor(Math.random() * COUNT);
      generation += 1;
      const seed = `wall-${generation}`;
      setSeeds((current) =>
        current.map((value, i) => (i === index ? seed : value)),
      );
    }, 1600);
    return () => clearInterval(timer);
  }, []);
  return (
    <div className="wall" aria-hidden="true">
      {seeds.map((seed, index) => (
        <span key={index} className="wall__cell">
          <span
            key={seed}
            className="wall__art"
            style={{ background: coverArt(seed).background }}
          />
        </span>
      ))}
    </div>
  );
}
