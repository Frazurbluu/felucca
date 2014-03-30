(ns battle-arena.state
  (:require [clojure.zip :as zip]
            [clojure.set :as set]
            [battle-arena.geometry :refer [point-in-triangle?
                                           point-distance]]))

(defn round [n decimals]
  (/ (Math.round (* n (Math.pow 10 decimals))) (Math.pow 10 decimals)))

(defn heterogeneous-zipper [m]
  (zip/zipper #(constantly true)
              #(seq (cond (map? %) (vals %) (vector? %) % :else nil))
              #(constantly %)
              m))

(defn index-of
  ([s value] (index-of s value 0))
  ([s value index]
   (when-not (empty? s)
     (if (= value (first s)) index (recur (rest s) value (inc index))))))

(defn heterogeneous-path
  ([zipper-path value] (heterogeneous-path (reverse zipper-path) value []))
  ([zipper-path value path]
   (if (empty? zipper-path)
     (reverse path)
     (let [next-value (first zipper-path)
           value-key (cond
                       (vector? next-value) (index-of next-value value)
                       (map? next-value) (get (set/map-invert next-value) value))]
       (recur (rest zipper-path) next-value (conj path value-key))))))

(defn path [state value]
  (loop [current-location (heterogeneous-zipper state)]
    (let [current-node (zip/node current-location)
          current-path (zip/path current-location)]
      (if (= (:id current-node) (:id value))
        (heterogeneous-path current-path current-node)
        (if (zip/end? current-location)
          nil
          (recur (zip/next current-location)))))))

(defn to-cursor [state value]
  {:state state :path (path @state value)})

(defn movement-speed [creature]
  (/ (:movement-speed creature) 300))

(defn coordinates-within-tile? [coordinates {tile-coordinates :coordinates
                                             tile-dimensions :dimensions}]
  (and
    (and (<= (:x tile-coordinates) (:x coordinates))
         (< (:x coordinates) (+ (:x tile-coordinates) (:width tile-dimensions))))
    (and (<= (:y tile-coordinates) (:y coordinates))
         (< (:y coordinates) (+ (:y tile-coordinates) (:height tile-dimensions))))))

(defn lane-tile-at [lane coordinates]
  (first
    (filter (partial coordinates-within-tile? coordinates) (:tiles lane))))

(defn distance [a b]
  (apply point-distance (map (juxt :x :y) (map :coordinates [a b]))))

(defn creep-within-tile? [creep tile]
  (coordinates-within-tile? (:coordinates creep) tile))

(defn creep-inside-two-subsequent-tiles-path?
  [creep [t0 t1 :as tiles]]
  (let [tiles-coordinates (map :coordinates tiles)
        cx (:x (:coordinates creep))
        cy (:y (:coordinates creep))
        t0x (:x (get-in t0 [:coordinates :x]))
        t0y (:y (get-in t0 [:coordinates :y]))
        t1x (:x (get-in t1 [:coordinates :x]))
        t1y (:y (get-in t1 [:coordinates :y]))]
    (or (some (partial creep-within-tile? creep) tiles)
        (if (= (- t0x t1x) (- t0y t1y)) ;; -45 degrees
          (or (point-in-triangle? [cx cy]
                                  [[(max t0x t1x) (min t0y t1y)]
                                   [(max t0x t1x) (max t0y t1y)]
                                   [(+ (max t0x t1x) (Math/abs (- t0x t1x)))
                                    (max t0y t1y)]])
              (point-in-triangle? [cx cy]
                                  [[(min t0x t1x) (max t0y t1y)]
                                   [(max t0x t1x) (max t0y t1y)]
                                   [(max t0x t1x)
                                    (+ (max t0y t1y) (Math/abs (- t0y t1y)))]]))
          (or (point-in-triangle? [cx cy]
                                  [[(min t0x t1x) (max t0y t1y)]
                                   [(max t0x t1x) (max t0y t1y)]
                                   [(max t0x t1x) (min t0y t1y)]])
              (point-in-triangle? [cx cy]
                                  [[(max t0x t1x)
                                    (+ (max t0y t1y) (Math/abs (- t0y t1y)))]
                                   [(max t0x t1x) (max t0y t1y)]
                                   [(+ (max t0x t1x) (Math/abs (- t0x t1x)))
                                    (max t0y t1y)]]))))))

(defn creep-within-lane? [creep {:keys [tiles]}]
  (some #(creep-inside-two-subsequent-tiles-path? creep %)
        (partition 2 1 tiles)))

(defn lane-tile-below-creep [{:keys [tiles]} creep]
  (first (filter (partial creep-within-tile? creep) tiles)))

;; FIXME improve performance.
(defn tile-closest-to-creep [tiles creep]
  (first (sort-by (partial distance creep) tiles)))

(defn lane-tile-closest-to-creep [lane creep]
  (first (sort-by (partial distance creep) (:tiles lane))))

(defn next-lane-tile [{:keys [tiles] :as lane} creep]
  (let [t (lane-tile-below-creep lane creep)]
    (if (= (:coordinates t) (:coordinates (last tiles)))
      (last tiles)
      (second (drop-while #(not= (:coordinates t) (:coordinates %)) tiles)))))

(defn closest-lane-tile [{:keys [tiles]} tile]
  (first (sort-by (partial distance tile) tiles)))

(defn next-lane-creep-destination [lane creep]
  (:coordinates (if (creep-within-lane? creep lane)
                  (next-lane-tile lane creep)
                  (closest-lane-tile lane creep))))

(defn move-towards! [state hero tile]
  (swap! state
         (fn [state hero tile]
           (update-in state
                      (path state hero)
                      merge
                      {:destination (:coordinates tile)}))
         hero
         tile))

(defn next-coordinates-delta [creature]
  (let [x0 (get-in creature [:coordinates :x])
        x1 (get-in creature [:destination :x])
        y0 (get-in creature [:coordinates :y])
        y1 (get-in creature [:destination :y])
        dx (- x1 x0)
        dy (- y1 y0)
        scale (Math/sqrt (+ (Math/pow dx 2) (Math/pow dy 2)))
        v (movement-speed creature)
        ndx (* v (/ dx scale))
        ndy (* v (/ dy scale))]
    {:x (cond
          (pos? dx) (if (> (+ x0 ndx) x1) dx ndx)
          (neg? dx) (if (< (+ x0 ndx) x1) dx ndx)
          :else 0)
     :y (cond
          (pos? dy) (if (> (+ y0 ndy) y1) dy ndy)
          (neg? dy) (if (< (+ y0 ndy) y1) dy ndy)
          :else 0)}))

(defn next-coordinates [creature]
  (merge-with + (:coordinates creature) (next-coordinates-delta creature)))

(defn next-hero-state [hero]
  (if-let [destination (:destination hero)]
    (merge hero
           (if (= (:coordinates destination) (:coordinates hero))
             {:destination nil}
             {:coordinates (next-coordinates hero)}))
    hero))

(defn next-creep-state [creep]
  (if-let [destination (:destination creep)]
    (merge creep
           (if (= (:coordinates destination) (:coordinates creep))
             {:destination nil}
             {:coordinates (next-coordinates creep)}))
    creep))

(defn next-creeps-state [creeps]
  (into [] (map next-creep-state creeps)))

(defn next-lane-creep-state [lane creep]
  (next-creep-state (merge creep
                           {:destination (next-lane-creep-destination lane
                                                                      creep)})))

(defn next-lane-creeps-state [lane creeps]
  (into [] (map (partial next-lane-creep-state lane) creeps)))

(defn next-lane-state [{:keys [tiles creeps] :as lane}]
  {:tiles tiles
   :creeps (next-lane-creeps-state lane creeps)})

(defn next-team-state [team])
(defn next-teams-state [teams] (map next-team-state teams))

(defn next-state [state]
  (-> state
      (update-in [:teams :dire :lanes :top] next-lane-state)
      (update-in [:teams :radiant :heroes :lion] next-hero-state)
      (update-in [:teams :dire :heroes :anti-mage] next-hero-state)))

(defn next-state! [state]
  (swap! state next-state))
