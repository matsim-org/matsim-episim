export const themeData = JSON.parse("{\"logo\":\"https://www.ivt.ethz.ch/forschung/matsim/_jcr_content/par/fullwidthimage/image.imageformat.1286.2026935405.png\",\"navbar\":[{\"text\":\"Home\",\"link\":\"/\"},{\"text\":\"Documentation\",\"link\":\"/documentation/introduction.html\"}],\"sidebar\":{\"/documentation/\":[{\"text\":\"Documentation\",\"collapsible\":false,\"children\":[\"/documentation/introduction.md\",\"/documentation/quickstart.md\",\"/documentation/repositorystructure.md\",\"/documentation/simulationsetup.md\",\"/documentation/episimsimulation.md\",\"/documentation/outputandanalysis.md\"]}]},\"locales\":{\"/\":{\"selectLanguageName\":\"English\"}},\"colorMode\":\"auto\",\"colorModeSwitch\":true,\"repo\":null,\"selectLanguageText\":\"Languages\",\"selectLanguageAriaLabel\":\"Select language\",\"sidebarDepth\":2,\"editLink\":true,\"editLinkText\":\"Edit this page\",\"lastUpdated\":true,\"contributors\":true,\"contributorsText\":\"Contributors\",\"notFound\":[\"There's nothing here.\",\"How did we get here?\",\"That's a Four-Oh-Four.\",\"Looks like we've got some broken links.\"],\"backToHome\":\"Take me home\",\"openInNewWindow\":\"open in new window\",\"toggleColorMode\":\"toggle color mode\",\"toggleSidebar\":\"toggle sidebar\"}")

if (import.meta.webpackHot) {
  import.meta.webpackHot.accept()
  if (__VUE_HMR_RUNTIME__.updateThemeData) {
    __VUE_HMR_RUNTIME__.updateThemeData(themeData)
  }
}

if (import.meta.hot) {
  import.meta.hot.accept(({ themeData }) => {
    __VUE_HMR_RUNTIME__.updateThemeData(themeData)
  })
}
