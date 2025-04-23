import comp from "/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/guide/example.html.vue"
const data = JSON.parse("{\"path\":\"/guide/example.html\",\"title\":\"Example\",\"lang\":\"en-US\",\"frontmatter\":{},\"git\":{},\"filePathRelative\":\"guide/example.md\"}")
export { comp, data }

if (import.meta.webpackHot) {
  import.meta.webpackHot.accept()
  if (__VUE_HMR_RUNTIME__.updatePageData) {
    __VUE_HMR_RUNTIME__.updatePageData(data)
  }
}

if (import.meta.hot) {
  import.meta.hot.accept(({ data }) => {
    __VUE_HMR_RUNTIME__.updatePageData(data)
  })
}
