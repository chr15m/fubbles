(ns fubbles.core
    (:require [reagent.core :as reagent :refer [atom]]
              [reagent.session :as session]
              [goog.events :as events]
              [goog.dom :as dom]
              [goog.history.EventType :as EventType]
              [cljs-uuid-utils.core :as uuid]
              [infinitelives.utils.console :refer [log]] 
              [infinitelives.utils.events :as evt]
              [infinitelives.utils.preload :as preload]
              [infinitelives.utils.file :refer [resources-to-urls]]
              [cljs.core.async :refer [chan <! >! put! close! timeout]])
    (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                     [infinitelives.utils.file :refer [get-file-list]])
    (:import goog.History))

; ***** pre-init section ***** ;

(enable-console-print!)

; ***** data section ***** ;
(defonce game-state (atom {:entities {}}))
(defonce viewport-size (atom {}))
(defonce players (atom {}))
(defonce sprites (atom {}))

; all images from disk
(def sprite-images (resources-to-urls (get-file-list "resources/public/img/sprites" "png")))

; ***** functions section ***** ;

; get a random number
(defn rnd [] (js/Math.random))

; inspect browser window for size
(defn re-calculate-viewport-size [old-viewport-size]
  (dom/getViewportSize (dom/getWindow)))

; get the current time
(defn get-time-now [] (.getTime (js/Date.)))

; make a sprite URL string
(defn sprite-url [s] (str "img/sprites/" (name s) ".png"))

; remove all entities of a particular type (useful for livecoding)
; e.g. :cloud :player :bubble
(defn remove-type [t]
  (swap! game-state assoc-in [:entities] (into {} (for [[id e] (@game-state :entities)] (if (not (= (e :type) t)) [id e])))))

; turn a position into a CSS "style" declaration
(defn compute-position-style [{[x y] :pos s :scale f :flip v :visibility i :img}]
  (let [w (.-width @viewport-size)
        h (.-height @viewport-size)
        sm (min w h)
        si (/ sm 1024.0)
        iw (* (.-width (get @sprites i)) s)
        ih (* (.-height (get @sprites i)) s)]
    ; (print (.-width (get @sprites i)))
    {:left (- (mod (+ (* x sm)
               (/ w 2.0)) (+ w iw)) iw)
     :top (- (mod (+ (* y sm)
              (/ h 2.0)) (+ h ih)) ih)
     :display (case v :visible "block" :invisible "none")
     :width (str (Math.round iw) "px")
     :height (str (Math.round ih) "px")
     :transform (str "scaleX(" f ")")}))

; insert a single new entity record into the game state and kick off its control loop
; entity-definition = :img :pos :type :scale :flip etc.
(defn make-entity [entity-definition]
  (let [id (uuid/uuid-string (uuid/make-random-uuid))
        entity {id (assoc entity-definition :id id :chan (chan) :visibility :visible)}]
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

; average all of the axes available so the user can use any control
(defn get-axis-value [!axes axis]
  (reduce + (for [a (range (/ (.-length !axes) 2))]
    (aget !axes (+ a axis)))))

; change the position of an entity based on the gamepad state
(defn update-position [old-state elapsed !axes axis]
  (if (= (get-axis-value !axes axis) 0)
    old-state
    (assoc-in old-state [:pos axis] (+ (get (old-state :pos) axis) (* (get-axis-value !axes axis) (/ elapsed 3000.0))))))

; change the flip direction depending on the gamepad axis
(defn update-sprite-direction [old-state !axes]
  (assoc-in old-state [:flip] (cond
                                (< (get-axis-value !axes 0) 0) -1
                                (> (get-axis-value !axes 0) 0) 1
                                true (old-state :flip))))

; calculation for which sprite frame to show
(defn calculate-player-image-frame-name [now index]
  (let [frame (+ (mod (js/Math.round (/ now (+ 900 (* index 300)))) 2) 1)
        img-name (str "c-" (+ index 1) "-" frame)]
    img-name))

; choose which frame to show
(defn update-animation-frame [old-state now gamepad-index !axes]
  (assoc-in old-state [:img] (calculate-player-image-frame-name now gamepad-index)))

; set the visibility flag
(defn update-visibility [old-state visibility]
  (assoc-in old-state [:visibility] visibility))

; update all of the player state depending on the gamepad input
(defn update-player [gamepad-object old-state elapsed now]
  (if-let [gamepad @gamepad-object]
    (let [!axes (.-axes gamepad)
          gamepad-index (.-index gamepad)]
      (-> old-state
          (update-position elapsed !axes 0)
          (update-position elapsed !axes 1)
          (update-visibility :visible)
          (update-animation-frame now gamepad-index !axes)
          (update-sprite-direction !axes)))
    ; set the player to invisible if their gamepad is disconnected
    (update-visibility old-state :invisible)))

; function to create a behaviour function that controls the entity with a gamepad
(defn make-gamepad-behaviour-fn [gamepad-object]
  (fn [old-state elapsed now]
    (update-player gamepad-object old-state elapsed now)))

; create a single player tied to a gamepad object
(defn make-player [gamepad-index gamepad-object]
  (log "Gamepad callback" gamepad-index @gamepad-object)
  (if @gamepad-object
    (let [gamepad-index (.-index @gamepad-object)]
      (if (not (@players gamepad-index))
        (do
          (log "Making player with gamepad:" gamepad-index)
          (let [player (make-entity {:type :player :img (calculate-player-image-frame-name 0 gamepad-index) :pos [0 0] :scale 0.2 :flip 1 :behaviour (make-gamepad-behaviour-fn gamepad-object) :gamepad-object gamepad-object})]
            (swap! players assoc-in [gamepad-index] player)
            player))))))

; behaviour that a cloud follows
(defn cloud-behaviour [old-state elapsed now]
  (-> old-state
      (update-position elapsed (clj->js (old-state :velocity)) 0)
      (update-position elapsed (clj->js (old-state :velocity)) 1)))

; select one of the cloud sprites randomly
(defn choose-cloud []
  (let [cloud-num (js/Math.round (+ (* 11 (rnd)) 1))]
    (str "cloud-" cloud-num)))

; create a single cloud object
(defn make-cloud []
  (make-entity {:type :cloud :img (choose-cloud) :pos [(* (- (rnd) 0.5) 2) (* (- (rnd) 0.5) 2)] :velocity [(* (- (rnd) 0.5) 0.2) (* (- (rnd) 0.5) 0.01)] :scale 0.4 :flip 1 :behaviour cloud-behaviour}))

; ***** renderer ***** ;

(defn dom-base []
  [:div
    [:div {:id "game-board"}
      ; if no gamepads are connected then draw the title screen
     (when (= (count @players) 0)
       [:span
         [:img#title {:src "img/sprites/title.png"}]
         [:img#gamepad {:src "img/sprites/gamepad.png"}]])
      ; DOM "scene grapher"
      (doall (map (fn [[id e]]
                    [:img {:class (str "sprite " (name (e :type)) "-sprite") :src (sprite-url (e :img)) :key id :style (compute-position-style e)}]) 
                  (:entities @game-state)))]])

; ***** launch ***** ;

; get the real viewport size for the first time
(swap! viewport-size re-calculate-viewport-size)

; update the current viewport size if it changes
(js/window.addEventListener "resize" #(swap! viewport-size re-calculate-viewport-size))

(evt/install-gamepad-listener make-player)

; add some cloud objects
(remove-type :cloud)
(doseq [c (range 10)] (make-cloud))

; get a handle on our progress bar
(def progress-bar (.getElementById js/document "progress-bar"))

(defn mount-root []
  (print "Mounting react root.")
  (reagent/render [dom-base] (.getElementById js/document "app")))

; pre-load all of our images
(defn preload-sprites []
  (let [c (preload/load-urls (for [[k v] (seq sprite-images)] v))]
    (go (loop []
          (let [image-result (<! c)]
            (when (not (nil? image-result))
              (let [[progress num-urls remaining img ev] image-result]
                (if (not (= (.-type ev) "load"))
                  (print "There was an error loading one of the resources")
                  (do
                    (<! (timeout (* (rnd) 200)))
                    (let [basename (.replace (last (.split (.-src img) "/")) ".png" "")]
                      (swap! sprites assoc basename img))
                    (set! progress-bar.style.width (str (Math.round (* (- 1.0 (/ remaining num-urls)) 100.0)) "%"))
                    (recur)))))))
        (print "Finished loading images.")
        (mount-root))))

; react init
(defn init! []
  (print "React init.")
  (preload-sprites))

