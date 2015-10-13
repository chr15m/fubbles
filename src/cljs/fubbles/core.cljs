(ns fubbles.core
    (:require [reagent.core :as reagent :refer [atom]]
              [reagent.session :as session]
              [goog.events :as events]
              [goog.dom :as dom]
              [goog.history.EventType :as EventType]
              [cljs-uuid-utils.core :as uuid]
              [infinitelives.utils.console :refer [log]] 
              [infinitelives.utils.events :as evt]
              [cljs.core.async :refer [chan <! timeout]])
    (:require-macros [cljs.core.async.macros :refer [go go-loop]])
    (:import goog.History))

; ***** pre-init section ***** ;

(enable-console-print!)

; ***** data section ***** ;
(defonce game-state (atom {:entities {}}))
(defonce viewport-size (atom {}))
(defonce players (atom {}))

; ***** functions section ***** ;

; inspect browser window for size
(defn re-calculate-viewport-size [old-viewport-size]
  (dom/getViewportSize (dom/getWindow)))

; get the current time
(defn get-time-now [] (.getTime (js/Date.)))

; make sprite URL
(defn sprite-url [s] (str "img/sprites/" (name s) ".png"))

; turn a position into a CSS "style" declaration
(defn compute-position-style [{[x y] :pos s :scale}]
  (print "re-computing position style")
  (let [w (.-width @viewport-size)
        h (.-height @viewport-size)
        si (/ 1024.0 (min w h))]
    {:top (mod (+ (* y h) 
              (/ h 2.0)) h)
     :left (mod (+ (* x w)
               (/ w 2.0)) w)
     :transform (str "scale(" (* s si) ")")}))

; insert a single new entity record into the game state and kick off its control loop
; entity-definition = :symbol :color :pos
(defn make-entity [entity-definition]
  (let [id (uuid/uuid-string (uuid/make-random-uuid))
        entity {id (assoc entity-definition :id id :chan (chan))}]
    ; swap the new entity definition into our game state
    (swap! game-state assoc-in
      [:entities id] (entity id))
    ; kick off the entity's control loop
    (go-loop [last-time (get-time-now)]
      ; run every 20 milliseconds
      (<! (timeout 20))
      (let [now (get-time-now)
            elapsed (- now last-time)]
        ; update this entity's properties according to its behaviour function
        (let [behaviour-fn (get-in @game-state [:entities id :behaviour])]
          (if (not (nil? behaviour-fn))
            (swap! game-state update-in [:entities id] behaviour-fn elapsed now)))
        (recur now)))
    ; return the entity we created
    entity))

; the function to update the position of an entity based on the gamepad
(defn update-position [old-state elapsed !axes axis]
  (assoc-in old-state [:pos axis] (+ (get (old-state :pos) axis) (* (aget !axes axis) (/ elapsed 3000.0)))))



; update all of the player state depending on the gamepad input
(defn update-player [gamepad-object old-state elapsed now]
  (let [!axes (.-axes gamepad-object)]
      (if (= (aget !axes 0) 0)
        old-state
        (-> old-state (update-position elapsed !axes 0) (update-position elapsed !axes 1)))))

; function to create a behaviour function that controls the entity with a gamepad
(defn make-gamepad-behaviour-fn [gamepad-object]
  (fn [old-state elapsed now]
    (update-player gamepad-object old-state elapsed now)))

; create a single player tied to a gamepad object
(defn make-player [gamepad-object]
  (let [gamepad-index (.-index gamepad-object)]
    (if (not (@players gamepad-index))
      (do
        (log "Making player with gamepad:" gamepad-index)
        (let [player (make-entity {:img (sprite-url (str "c-" (+ gamepad-index 1) "-1")) :pos [0 0] :scale 0.2 :behaviour (make-gamepad-behaviour-fn gamepad-object)})]
          (swap! players assoc-in [gamepad-index] player)
          player)))))

; ***** renderer ***** ;

(defn dom-base []
  [:div
    [:div {:id "game-board"}
      ; DOM "scene grapher"
      (doall (map (fn [[id e]]
                    [:img {:class "sprite"
                           :key id
                           :style (compute-position-style e)
                           :src (e :img)}])
                  (:entities @game-state)))]])

; ***** launch ***** ;

; get the real viewport size for the first time
(swap! viewport-size re-calculate-viewport-size)

; update the current viewport size if it changes
(js/window.addEventListener "resize" #(swap! viewport-size (fn [old-viewport-size] (dom/getViewportSize (dom/getWindow)))))

; create players for any gamepads that are already connected
(let [gamepads (js/navigator.getGamepads)]
  (log "Initial gamepads:" gamepads)
  (doseq [gamepad-index (range 4)]
    (let [gamepad-data (aget gamepads gamepad-index)]
      (if gamepad-data (log (make-player gamepad-data))))))

; create players as gamepads are added
(js/window.addEventListener "gamepadconnected" (fn [ev] (log "Gamepad connected:" ev.gamepad.index ev.gamepad.id ev) (log (make-player ev.gamepad))))

; launch a go-loop that will just continuously refresh the gamepads
(go-loop [] (<! (timeout 20)) (js/navigator.getGamepads) (recur))

; react init
(defn init! []
  (reagent/render [dom-base] (.getElementById js/document "app")))

