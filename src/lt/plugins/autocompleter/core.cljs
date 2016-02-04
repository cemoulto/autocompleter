(ns lt.plugins.autocompleter.core
  "Core namespace for the autocompleter plugin."
  (:require [lt.objs.editor :as editor]
            [lt.objs.editor.pool :as pool]
            [lt.objs.command :as cmd]
            [lt.object :as object]
            [lt.util.js :as jsutil]
            [cljs.core.async :as async :refer [<! put! chan timeout]])
  (:require-macros [lt.macros :refer [behavior background defui]]
                    [cljs.core.async.macros :refer [go go-loop]]))


;; TODO: Needs to be configurable (keymap and preferences for completeSingle etc)
(def default-options
  {:async true
   :completeSingle false
   :supportsSelection true
   :closeOnUnfocus true
   :customKeys {:Up #((.-moveFocus %2) -1 )
                :Down #((.-moveFocus %2) 1)
                :Enter #((.-pick %2))
                :Tab #((.-pick %2))
                :Esc #((.-close %2 ))}})



(defn- distinct-completions [hints]
  (let [seen #js {}]
    (filter (fn [hint]
              (if (true? (aget seen (:completion hint)))
                false
                (aset seen (:completion hint) true)))
            hints)))

;; TODO:
;; - Configurable sorting
;; - Configurable number of results ?
;; - Configurable max completion length
(defn- process-hint-results
  "Prepare found hints for display through CM showHint"
  [hints]
  (when (seq hints)
    (->> hints
         (filter #(< (.-length (:completion %)) 1000))
         distinct-completions
         (sort-by :text)
         (take 25)
         (hash-map :list)
         (#(let [fst (-> % :list first)]
             (assoc % :from (:from fst) :to (:to fst)))) ;; allthough we've enforced from-to on each item, CM insists on having this props at root level to
         clj->js)))


(defn- listen-for-hint-results
  "Listen for hint results from all given channels
  Will raise a behavior to show results when all channels have delivered a result
  or alternatively the specified timeout has elapsed"
  [ed channels]
  (let [t (timeout 1000)] ;; should be configurable ?
    (go-loop [all-results []
              chs (conj channels t)]
      (if (= 1 (count chs))
        (object/raise ed :show-hint-results all-results)
        (let [[res source] (async/alts! chs)]
          (if (identical? source t)
            (object/raise ed :show-hint-results all-results)
            (recur (into all-results res) (remove #{source} chs))))))))



(defn- pos-obj->pos [p]
  {:ch (.-ch p)
   :line (.-line p)})


(defn- from-cm-hints [res-obj]
  (if res-obj
    (let [res (js->clj res-obj :keywordize-keys true)
         from (pos-obj->pos (:from res)) ; DOH, statement above doesn't traverse into the from to js objects
         to (pos-obj->pos (:to res))]
     (map #(hash-map :text %
                     :completion %
                     :from from
                     :to to)
          (:list res)))
    []))


;; TODO: This needs to be configurable per hint provider
(defn- get-token [ed]
  (editor/->token ed (editor/->cursor ed)))

;; TODO: This needs to be configurable per hint provider
(defn- should-hint? [ed]
  (-> (get-token ed) :string (.trim) seq))

(defn- completion-active? [ed]
  (.-completionActive (.-state (editor/->cm-ed ed))))

(defn- maybe-close-hinter [ed]
  (when-let [state (completion-active? ed)]
    (.close state)))


(behavior ::javascript-hints
          :triggers #{:init-hints}
          :desc "Autocompleter: JavaScript Hints"
          :reaction (fn [ed channels]
                      (if (should-hint? ed)
                        (let [ch (chan)]
                         (put! ch (-> (.javascript js/CodeMirror.hint (editor/->cm-ed ed))
                                      from-cm-hints))
                         (conj channels ch))
                        channels)))

(behavior ::anyword-hints
          :triggers #{:init-hints}
          :desc "Autocompleter: Anyword Hints"
          :reaction (fn [ed channels]
                      (if (should-hint? ed)
                        (let [ch (chan)]
                         (put! ch (-> (.anyword js/CodeMirror.hint (editor/->cm-ed ed))
                                      from-cm-hints))
                         (conj channels ch))
                        channels)))




(behavior ::show-hint-results
          :triggers #{:show-hint-results}
          :desc "Autocompleter: Show the autocompleter popup with the given hints"
          :reaction (fn [ed hints]
                      (maybe-close-hinter ed)
                      (when-let [processed-hints (process-hint-results hints)]
                        (js/CodeMirror.showHint (editor/->cm-ed ed)
                                                (fn [_ cb] (cb processed-hints))
                                                (clj->js default-options)))))

(defn- on-line-change [line ch]
  (let [ed (pool/last-active)]
    (if-not (completion-active? ed)
      (js/CodeMirror.off line "change" on-line-change)
      (when (= "+delete" (.-origin ch)) ;; TODO: should probably handle paste and other things to
        (object/raise ed :start-hinting)))))

(behavior ::start-hinting
          :triggers #{:start-hinting}
          :desc "Autocompleter: Start hinting"
          :reaction (fn [ed]
                      (let [pos (editor/->cursor ed)
                            line-handle (editor/line-handle ed (:line pos))
                            chs (object/raise-reduce ed :init-hints [])]
                        (js/CodeMirror.off line-handle "change" on-line-change)
                        ;; YODO: Be nice to check if a proper channel was returned
                        (when (seq chs)
                          (listen-for-hint-results ed chs)
                          (js/CodeMirror.on line-handle "change" on-line-change)))))


(behavior ::auto-show-on-input
          :triggers #{:input}
          :desc "Autocompleter: Show on change"
          :reaction (fn [ed _ ch]
                      (object/raise ed :start-hinting)))
