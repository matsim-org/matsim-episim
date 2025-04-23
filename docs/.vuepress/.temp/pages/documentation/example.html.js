import comp from "/Users/friedrichvolkers/GIT/matsim-episim/docs/.vuepress/.temp/pages/documentation/example.html.vue"
const data = JSON.parse("{\"path\":\"/documentation/example.html\",\"title\":\"Example\",\"lang\":\"en-US\",\"frontmatter\":{},\"git\":{},\"filePathRelative\":\"documentation/example.md\"}")
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
